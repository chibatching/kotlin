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

import com.google.dart.compiler.backend.js.ast.JsScope
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.js.translate.context.TranslationContext
import org.jetbrains.kotlin.js.translate.general.Translation
import org.jetbrains.kotlin.js.translate.general.TranslatorVisitor
import org.jetbrains.kotlin.js.translate.initializer.InitializerUtils.generateInitializerForDelegate
import org.jetbrains.kotlin.js.translate.initializer.InitializerUtils.generateInitializerForProperty
import org.jetbrains.kotlin.js.translate.initializer.InitializerVisitor
import org.jetbrains.kotlin.js.translate.utils.BindingUtils.getFunctionDescriptor
import org.jetbrains.kotlin.js.translate.utils.BindingUtils.getPropertyDescriptor
import org.jetbrains.kotlin.js.translate.utils.JsAstUtils
import org.jetbrains.kotlin.psi.KtAnonymousInitializer
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty

class FileDeclarationVisitor(
        val context: TranslationContext,
        val scope: JsScope
) : TranslatorVisitor<Unit>() {

    private val initializer = JsAstUtils.createFunctionWithEmptyBody(context.scope())
    private val initializerContext = context.contextWithScope(initializer).innerBlock(initializer.body)
    private val initializerVisitor = InitializerVisitor()

    override fun emptyResult(context: TranslationContext) {}

    override fun visitClassOrObject(declaration: KtClassOrObject, context: TranslationContext?) {
        ClassTranslator.translate(declaration, context!!)
    }

    override fun visitProperty(expression: KtProperty, context: TranslationContext) {
        super.visitProperty(expression, context)
        val initializer = expression.initializer
        if (initializer != null) {
            val value = Translation.translateAsExpression(initializer, initializerContext)
            val propertyDescriptor: PropertyDescriptor = getPropertyDescriptor(context.bindingContext(), expression)
            this.initializer.body.statements += generateInitializerForProperty(context, propertyDescriptor, value)
        }

        generateInitializerForDelegate(context, expression)?.let { this.initializer.body.statements += it }
    }

    override fun visitAnonymousInitializer(expression: KtAnonymousInitializer, context: TranslationContext?) {
        expression.accept(initializerVisitor, initializerContext)
    }

    override fun visitNamedFunction(expression: KtNamedFunction, context: TranslationContext) {
        val descriptor = getFunctionDescriptor(context.bindingContext(), expression)
        if (descriptor.modality === Modality.ABSTRACT) return

        val innerContext = context.newDeclaration(descriptor)
        val function = context.declareTopLevelFunction(descriptor)
        val functionExpression = Translation.functionTranslator(expression, innerContext, function).translateAsMethod()
        if (functionExpression == function) {
            context.addRootStatement(function.makeStmt())
        }
        else {
            context.addRootStatement(JsAstUtils.newVar(function.name, functionExpression))
        }
    }
}
