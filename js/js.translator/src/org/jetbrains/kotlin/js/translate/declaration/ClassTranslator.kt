/*
 * Copyright 2010-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.js.translate.declaration

import com.google.dart.compiler.backend.js.ast.*
import com.intellij.util.SmartList
import org.jetbrains.kotlin.backend.common.CodegenUtil
import org.jetbrains.kotlin.backend.common.bridges.Bridge
import org.jetbrains.kotlin.backend.common.bridges.generateBridgesForFunctionDescriptor
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.builtins.functions.FunctionClassDescriptor
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.js.descriptorUtils.hasPrimaryConstructor
import org.jetbrains.kotlin.js.translate.callTranslator.CallTranslator
import org.jetbrains.kotlin.js.translate.context.DeferredCallSite
import org.jetbrains.kotlin.js.translate.context.Namer
import org.jetbrains.kotlin.js.translate.context.TranslationContext
import org.jetbrains.kotlin.js.translate.context.getNameForCapturedDescriptor
import org.jetbrains.kotlin.js.translate.expression.FunctionTranslator
import org.jetbrains.kotlin.js.translate.general.AbstractTranslator
import org.jetbrains.kotlin.js.translate.initializer.ClassInitializerTranslator
import org.jetbrains.kotlin.js.translate.utils.BindingUtils
import org.jetbrains.kotlin.js.translate.utils.BindingUtils.getClassDescriptor
import org.jetbrains.kotlin.js.translate.utils.BindingUtils.getPropertyDescriptorForConstructorParameter
import org.jetbrains.kotlin.js.translate.utils.FunctionBodyTranslator
import org.jetbrains.kotlin.js.translate.utils.JsAstUtils
import org.jetbrains.kotlin.js.translate.utils.JsDescriptorUtils.getSupertypesWithoutFakes
import org.jetbrains.kotlin.js.translate.utils.PsiUtils.getPrimaryConstructorParameters
import org.jetbrains.kotlin.js.translate.utils.TranslationUtils.simpleReturnFunction
import org.jetbrains.kotlin.js.translate.utils.generateDelegateCall
import org.jetbrains.kotlin.js.translate.utils.jsAstUtils.toInvocationWith
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtSecondaryConstructor
import org.jetbrains.kotlin.resolve.BindingContextUtils
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.resolve.DescriptorUtils.*
import org.jetbrains.kotlin.types.CommonSupertypes.topologicallySortSuperclassesAndRecordAllInstances
import org.jetbrains.kotlin.types.SimpleType
import org.jetbrains.kotlin.types.TypeConstructor
import org.jetbrains.kotlin.utils.DFS
import org.jetbrains.kotlin.utils.identity

/**
 * Generates a definition of a single class.
 */
class ClassTranslator private constructor(
        private val classDeclaration: KtClassOrObject,
        context: TranslationContext
) : AbstractTranslator(context) {

    private val descriptor = getClassDescriptor(context.bindingContext(), classDeclaration)
    private val secondaryConstructors = mutableListOf<ConstructorInfo>()
    private var primaryConstructor: ConstructorInfo? = null

    private fun translate() {
        generateClassCreateInvocationArguments()
    }

    private fun isTrait(): Boolean = descriptor.kind == ClassKind.INTERFACE

    private fun isAnnotation(): Boolean = descriptor.kind == ClassKind.ANNOTATION_CLASS

    private fun generateClassCreateInvocationArguments() {
        val properties = SmartList<JsPropertyInitializer>()

        val scope = context().getScopeForDescriptor(descriptor)
        val context = context().newDeclaration(descriptor)

        val nonConstructorContext = context.innerWithUsageTracker(scope, descriptor)
        nonConstructorContext.startDeclaration()
        val delegationTranslator = DelegationTranslator(classDeclaration, nonConstructorContext)
        translatePropertiesAsConstructorParameters(nonConstructorContext, properties)
        val bodyVisitor = DeclarationBodyVisitor(scope, descriptor)
        bodyVisitor.traverseContainer(classDeclaration, nonConstructorContext)
        delegationTranslator.generateDelegated(properties)

        translatePrimaryConstructor(context, delegationTranslator)
        addMetadataObject()
        context.addClass(descriptor)
        addSuperclassReferences()
        classDeclaration.getSecondaryConstructors().forEach { generateSecondaryConstructor(context, it) }
        generatedBridgeMethods(properties)

        if (descriptor.isData) {
            JsDataClassGenerator(classDeclaration, context, properties).generate()
        }

        if (isEnumClass(descriptor)) {
            val enumEntries = JsObjectLiteral(bodyVisitor.enumEntryList, true)
            context.addRootStatement(simpleReturnFunction(nonConstructorContext.getScopeForDescriptor(descriptor), enumEntries).makeStmt())
        }

        emitConstructors(nonConstructorContext, nonConstructorContext.endDeclaration())
        for (constructor in allConstructors) {
            addClosureParameters(constructor, nonConstructorContext)
        }
    }

    private fun translatePrimaryConstructor(classContext: TranslationContext, delegationTranslator: DelegationTranslator) {
        val constructorFunction = classContext.addRootFunction(descriptor)

        if (!isTrait()) {
            val constructorContext = classContext.innerWithUsageTracker(constructorFunction.scope, descriptor)
            ClassInitializerTranslator(classDeclaration, constructorContext, constructorFunction)
                    .generateInitializeMethod(delegationTranslator)
            this.primaryConstructor = ConstructorInfo(constructorFunction, constructorContext, descriptor)
        }
    }

    private fun addMetadataObject() {
        context().addRootStatement(JsAstUtils.assignment(createMetadataRef(), JsObjectLiteral()).makeStmt())
    }

    private fun createMetadataRef() = JsNameRef(Namer.METADATA, JsNameRef(context().getInnerNameForDescriptor(descriptor)))

    private fun generateSecondaryConstructor(classContext: TranslationContext, constructor: KtSecondaryConstructor) {
        // Prepare
        val constructorDescriptor = BindingUtils.getDescriptorForElement(classContext.bindingContext(), constructor)
                as ClassConstructorDescriptor
        val classDescriptor = constructorDescriptor.containingDeclaration

        val constructorScope = classContext.getScopeForDescriptor(constructorDescriptor)

        val thisName = constructorScope.declareName(Namer.ANOTHER_THIS_PARAMETER_NAME)
        val thisNameRef = thisName.makeRef()
        val receiverDescriptor = classDescriptor.thisAsReceiverParameter

        var context = classContext
                .innerWithUsageTracker(constructorScope, constructorDescriptor)
                .innerContextWithAliased(receiverDescriptor, thisNameRef)

        val outerClassName = context.getOuterClassReference(classDescriptor)
        val outerClass = DescriptorUtils.getContainingClass(classDescriptor)
        if (outerClassName != null) {
            val outerClassReceiver = outerClass!!.thisAsReceiverParameter
            context = context.innerContextWithAliased(outerClassReceiver, outerClassName.makeRef())
        }

        // Translate constructor body
        val constructorInitializer = context.addRootFunction(constructorDescriptor)
        FunctionTranslator.newInstance(constructor, context, constructorInitializer).translateAsMethodWithoutMetadata()

        // Translate super/this call
        val superCallGenerators = mutableListOf<(MutableList<JsStatement>) -> Unit>()
        val referenceToClass = context.getQualifiedReference(classDescriptor)

        superCallGenerators += { it += FunctionBodyTranslator.setDefaultValueForArguments(constructorDescriptor, context) }

        val createInstance = Namer.createObjectWithPrototypeFrom(referenceToClass)
        val instanceVar = JsAstUtils.assignment(thisNameRef, JsAstUtils.or(thisNameRef, createInstance)).makeStmt()
        superCallGenerators += { it += instanceVar }

        // Add parameter for outer instance
        val leadingArgs = mutableListOf<JsExpression>()

        if (outerClassName != null) {
            constructorInitializer.parameters.add(0, JsParameter(outerClassName))
            leadingArgs += outerClassName.makeRef()
        }

        constructorInitializer.parameters += JsParameter(thisName)

        // Generate super/this call to insert to beginning of the function
        val resolvedCall = BindingContextUtils.getDelegationConstructorCall(context.bindingContext(), constructorDescriptor)
        val delegationClassDescriptor = (resolvedCall?.resultingDescriptor as? ClassConstructorDescriptor)?.constructedClass

        if (resolvedCall != null && !KotlinBuiltIns.isAny(delegationClassDescriptor!!)) {
            superCallGenerators += {
                val delegationConstructor = resolvedCall.resultingDescriptor
                it += CallTranslator.translate(context, resolvedCall)
                        .toInvocationWith(leadingArgs, delegationConstructor.valueParameters.size, thisNameRef).makeStmt()
            }
        }

        val delegationCtorInTheSameClass = delegationClassDescriptor == classDescriptor
        if (!delegationCtorInTheSameClass && !classDescriptor.hasPrimaryConstructor()) {
            superCallGenerators += {
                val usageTracker = context.usageTracker()!!
                val closure = context.getClassOrConstructorClosure(classDescriptor).orEmpty().map {
                    usageTracker.getNameForCapturedDescriptor(it)!!.makeRef()
                }
                it += JsInvocation(Namer.getFunctionCallRef(referenceToClass), listOf(thisNameRef) + closure + leadingArgs).makeStmt()
            }
        }

        constructorInitializer.body.statements += JsReturn(thisNameRef)

        val compositeSuperCallGenerator: () -> Unit = {
            val additionalStatements = mutableListOf<JsStatement>()
            for (partGenerator in superCallGenerators) {
                partGenerator(additionalStatements)
            }
            constructorInitializer.body.statements.addAll(0, additionalStatements)
        }

        secondaryConstructors += ConstructorInfo(constructorInitializer, context, constructorDescriptor, compositeSuperCallGenerator)
    }

    private val allConstructors: Sequence<ConstructorInfo>
        get() {
            val primary = primaryConstructor
            return if (primary != null) sequenceOf(primary) + secondaryConstructors else secondaryConstructors.asSequence()
        }

    private fun emitConstructors(nonConstructorContext: TranslationContext, callSites: List<DeferredCallSite>) {
        // Build map that maps constructor to all constructors called via this()
        val constructorMap = allConstructors.map { it.descriptor to it }.toMap()

        val callSiteMap = callSites.groupBy {
            val constructor =  it.constructor
            if (constructor.isPrimary) constructor.containingDeclaration else constructor
        }

        val thisCalls = secondaryConstructors.map {
            val set = mutableSetOf<ConstructorInfo>()
            val descriptor = it.descriptor
            if (descriptor is ConstructorDescriptor) {
                val resolvedCall = BindingContextUtils.getDelegationConstructorCall(context().bindingContext(), descriptor)
                if (resolvedCall != null) {
                    val callee = constructorMap[resolvedCall.resultingDescriptor]
                    if (callee != null) {
                        set += callee
                    }
                }
            }
            Pair(it, set)
        }.toMap()

        val sortedConstructors = DFS.topologicalOrder(allConstructors.asIterable()) { thisCalls[it].orEmpty() }.reversed()
        for (constructor in sortedConstructors) {
            constructor.superCallGenerator()

            val nonConstructorUsageTracker = nonConstructorContext.usageTracker()!!
            val usageTracker = constructor.context.usageTracker()!!

            val nonConstructorCapturedVars = nonConstructorUsageTracker.capturedDescriptors
            val constructorCapturedVars = usageTracker.capturedDescriptors

            val capturedVars = (nonConstructorCapturedVars + constructorCapturedVars).distinct()

            val descriptor = constructor.descriptor
            val classDescriptor = DescriptorUtils.getParentOfType(descriptor, ClassDescriptor::class.java, false)!!
            nonConstructorContext.putClassOrConstructorClosure(descriptor, capturedVars)

            val constructorCallSites = callSiteMap[constructor.descriptor].orEmpty()

            for (callSite in constructorCallSites) {
                val closureQualifier = callSite.context.getArgumentForClosureConstructor(classDescriptor.thisAsReceiverParameter)
                capturedVars.forEach { nonConstructorUsageTracker.used(it) }
                val closureArgs = capturedVars.map {
                    val name = nonConstructorUsageTracker.getNameForCapturedDescriptor(it)!!
                    JsAstUtils.pureFqn(name, closureQualifier)
                }
                callSite.invocationArgs.addAll(0, closureArgs.toList())
            }
        }
    }

    private fun addClosureParameters(constructor: ConstructorInfo, nonConstructorContext: TranslationContext) {
        val usageTracker = constructor.context.usageTracker()!!
        val capturedVars = context().getClassOrConstructorClosure(constructor.descriptor) ?: return
        val nonConstructorUsageTracker = nonConstructorContext.usageTracker()!!

        val function = constructor.function
        val additionalStatements = mutableListOf<JsStatement>()
        for ((i, capturedVar) in capturedVars.withIndex()) {
            val fieldName = nonConstructorUsageTracker.capturedDescriptorToJsName[capturedVar]
            val name = usageTracker.capturedDescriptorToJsName[capturedVar] ?: fieldName!!

            function.parameters.add(i, JsParameter(name))
            if (fieldName != null && constructor == primaryConstructor) {
                additionalStatements += JsAstUtils.defineSimpleProperty(fieldName.ident, name.makeRef())
            }
        }

        function.body.statements.addAll(0, additionalStatements)
    }

    private fun addSuperclassReferences() {
        val supertypeReferences = JsArrayLiteral(getSupertypesNameReferences())
        val assignment = JsAstUtils.assignment(JsNameRef(Namer.METADATA_SUPERTYPES, createMetadataRef()), supertypeReferences);
        context().addRootStatement(assignment.makeStmt());
    }

    private fun getSupertypesNameReferences(): List<JsExpression> {
        val supertypes = getSupertypesWithoutFakes(descriptor)
                .filter { it.constructor.declarationDescriptor !is FunctionClassDescriptor }
        if (supertypes.isEmpty()) {
            return emptyList()
        }
        if (supertypes.size == 1) {
            val type = supertypes[0]
            val supertypeDescriptor = getClassDescriptorForType(type)
            return listOf(JsNameRef(context().getInnerNameForDescriptor(supertypeDescriptor)))
        }

        val supertypeConstructors = mutableSetOf<TypeConstructor>()
        for (type in supertypes) {
            supertypeConstructors += type.constructor
        }
        val sortedAllSuperTypes = topologicallySortSuperclassesAndRecordAllInstances(
            descriptor.defaultType,
            mutableMapOf<TypeConstructor, Set<SimpleType>>(),
            mutableSetOf<TypeConstructor>()
        )
        val supertypesRefs = mutableListOf<JsExpression>()
        for (typeConstructor in sortedAllSuperTypes) {
            if (supertypeConstructors.contains(typeConstructor)) {
                val supertypeDescriptor = getClassDescriptorForTypeConstructor(typeConstructor)
                supertypesRefs += JsNameRef(context().getInnerNameForDescriptor(supertypeDescriptor))
            }
        }
        return supertypesRefs
    }

    private fun translatePropertiesAsConstructorParameters(classDeclarationContext: TranslationContext,
                                                           result: MutableList<JsPropertyInitializer>) {
        for (parameter in getPrimaryConstructorParameters(classDeclaration)) {
            val descriptor = getPropertyDescriptorForConstructorParameter(bindingContext(), parameter)
            if (descriptor != null) {
                translateAccessors(descriptor, result, classDeclarationContext)
            }
        }
    }

    private fun generatedBridgeMethods(properties: MutableList<JsPropertyInitializer>) {
        if (isAnnotation()) return

        generateBridgesToTraitImpl(properties)

        generateOtherBridges(properties)
    }

    private fun generateBridgesToTraitImpl(properties: MutableList<JsPropertyInitializer>) {
        for ((key, value) in CodegenUtil.getNonPrivateTraitMethods(descriptor)) {
            if (!areNamesEqual(key, value)) {
                properties += generateDelegateCall(value, key, JsLiteral.THIS, context())
            }
        }
    }

    private fun generateOtherBridges(properties: MutableList<JsPropertyInitializer>) {
        for (memberDescriptor in descriptor.defaultType.memberScope.getContributedDescriptors()) {
            if (memberDescriptor is FunctionDescriptor) {
                val bridgesToGenerate = generateBridgesForFunctionDescriptor(memberDescriptor, identity()) {
                    //There is no DefaultImpls in js backend so if method non-abstract it should be recognized as non-abstract on bridges calculation
                    false
                }

                for (bridge in bridgesToGenerate) {
                    generateBridge(bridge, properties)
                }
            }
        }
    }

    private fun generateBridge(bridge: Bridge<FunctionDescriptor>, properties: MutableList<JsPropertyInitializer>) {
        val fromDescriptor = bridge.from
        val toDescriptor = bridge.to
        if (areNamesEqual(fromDescriptor, toDescriptor)) return

        if (fromDescriptor.kind.isReal && fromDescriptor.modality != Modality.ABSTRACT && !toDescriptor.kind.isReal)
            return

        properties += generateDelegateCall(fromDescriptor, toDescriptor, JsLiteral.THIS, context())
    }

    private fun areNamesEqual(first: FunctionDescriptor, second: FunctionDescriptor): Boolean {
        val firstName = context().getNameForDescriptor(first)
        val secondName = context().getNameForDescriptor(second)
        return firstName.ident == secondName.ident
    }

    companion object {
        @JvmStatic fun translate(classDeclaration: KtClassOrObject, context: TranslationContext) {
            return ClassTranslator(classDeclaration, context).translate()
        }
    }

    private class ConstructorInfo(
            val function: JsFunction,
            val context: TranslationContext,
            val descriptor: MemberDescriptor,
            val superCallGenerator: (() -> Unit) = { }
    )
}
