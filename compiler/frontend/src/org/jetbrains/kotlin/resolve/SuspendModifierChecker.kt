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

package org.jetbrains.kotlin.resolve

import org.jetbrains.kotlin.builtins.getReceiverTypeFromFunctionType
import org.jetbrains.kotlin.builtins.getReturnTypeFromFunctionType
import org.jetbrains.kotlin.builtins.isExtensionFunctionType
import org.jetbrains.kotlin.coroutines.isValidContinuation
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.diagnostics.DiagnosticSink
import org.jetbrains.kotlin.diagnostics.Errors
import org.jetbrains.kotlin.incremental.KotlinLookupLocation
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtDeclarationWithBody
import org.jetbrains.kotlin.types.typeUtil.isUnit
import org.jetbrains.kotlin.util.OperatorNameConventions
import org.jetbrains.kotlin.utils.sure

object SuspendModifierChecker : SimpleDeclarationChecker {
    private val ALLOW_SUSPEND_EXTENSIONS_ANNOTATION_FQ_NAME = FqName("kotlin.coroutines.AllowSuspendExtensions")

    override fun check(
            declaration: KtDeclaration,
            descriptor: DeclarationDescriptor,
            diagnosticHolder: DiagnosticSink,
            bindingContext: BindingContext
    ) {
        val functionDescriptor = descriptor as? FunctionDescriptor ?: return
        if (!functionDescriptor.isSuspend) return

        val suspendModifierElement = declaration.modifierList?.getModifier(KtTokens.SUSPEND_KEYWORD).sure { declaration.text }
        fun report(message: String) {
            diagnosticHolder.report(Errors.INAPPLICABLE_MODIFIER.on(suspendModifierElement, KtTokens.SUSPEND_KEYWORD, message))
        }

        if (functionDescriptor.dispatchReceiverParameter == null) {
            if (functionDescriptor.extensionReceiverParameter == null) {
                report("function must be either a class member or an extension")
                return
            }

            val classDescriptor =
                    functionDescriptor.extensionReceiverParameter!!.type.constructor.declarationDescriptor as? ClassDescriptor
            if (classDescriptor == null) {
                report("function must be an extension to class")
                return
            }

            if (!classDescriptor.annotations.hasAnnotation(ALLOW_SUSPEND_EXTENSIONS_ANNOTATION_FQ_NAME)) {
                report("controller class must be annotated with AllowSuspendExtensions annotation")
                return
            }
        }

        val continuationParameterType = functionDescriptor.valueParameters.lastOrNull()?.type
        val isValidContinuation = continuationParameterType?.isValidContinuation() ?: false
        if (!isValidContinuation) {
            report("last parameter of suspend function should have a type of Continuation<T>")
            return
        }

        if (continuationParameterType?.arguments?.firstOrNull()?.isStarProjection == true) {
            report("Continuation<*> is prohibited as a last parameter of suspend function")
        }

        if (functionDescriptor.returnType?.isUnit() != true) {
            report("return type of suspension function must be a kotlin.Unit, but ${functionDescriptor.returnType} was found")
        }
    }
}

object CoroutineModifierChecker : SimpleDeclarationChecker {
    override fun check(
            declaration: KtDeclaration,
            descriptor: DeclarationDescriptor,
            diagnosticHolder: DiagnosticSink,
            bindingContext: BindingContext
    ) {
        val functionDescriptor = descriptor as? FunctionDescriptor ?: return
        if (declaration !is KtDeclarationWithBody) return
        val location = KotlinLookupLocation(declaration)

        for ((parameterDescriptor, parameterDeclaration) in functionDescriptor.valueParameters.zip(declaration.valueParameters)) {
            if (!parameterDescriptor.isCoroutine) continue
            val coroutineModifier = parameterDeclaration.modifierList?.getModifier(KtTokens.COROUTINE_KEYWORD) ?: continue

            fun report(message: String) {
                diagnosticHolder.report(Errors.INAPPLICABLE_MODIFIER.on(coroutineModifier, KtTokens.COROUTINE_KEYWORD, message))
            }

            if (!parameterDescriptor.type.isExtensionFunctionType) {
                report("parameter should have function type with extension like 'Controller.() -> Continuation<Unit>'")
                continue
            }

            val returnType = getReturnTypeFromFunctionType(parameterDescriptor.type)

            if (returnType.isMarkedNullable || !returnType.isValidContinuation() || !returnType.arguments.single().type.isUnit()) {
                report("parameter should have function type like 'Controller.() -> Continuation<Unit>' (Continuation<Unit> for return type is necessary)")
                continue
            }

            if (functionDescriptor.isInline && !parameterDescriptor.isNoinline) {
                report("coroutine parameter of inline function should be marked as 'noinline'")
                continue
            }

            val controller = getReceiverTypeFromFunctionType(parameterDescriptor.type)!!

            val handleResultFunctions =
                    controller.memberScope
                            .getContributedFunctions(OperatorNameConventions.COROUTINE_HANDLE_RESULT, location)
                            .filter { it.isOperator }

            if (handleResultFunctions.size > 1) {
                report("only one operator handleResult should be declared for controller, but ${handleResultFunctions.size} found")
                continue
            }
        }
    }
}
