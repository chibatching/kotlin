/*
 * Copyright 2010-2015 JetBrains s.r.o.
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

package org.jetbrains.kotlin.resolve;

import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.psi.KtFile;
import org.jetbrains.kotlin.psi.KtScript;
import org.jetbrains.kotlin.resolve.calls.smartcasts.DataFlowInfoFactory;
import org.jetbrains.kotlin.resolve.lazy.ImportResolver;
import org.jetbrains.kotlin.resolve.lazy.KotlinCodeAnalyzer;

import java.util.Collection;

public class LazyTopDownAnalyzerForTopLevel {
    private final KotlinCodeAnalyzer codeAnalyzer;
    private final LazyTopDownAnalyzer lazyTopDownAnalyzer;

    public LazyTopDownAnalyzerForTopLevel(
            @NotNull LazyTopDownAnalyzer lazyTopDownAnalyzer,
            @NotNull KotlinCodeAnalyzer codeAnalyzer
    ) {
        this.lazyTopDownAnalyzer = lazyTopDownAnalyzer;
        this.codeAnalyzer = codeAnalyzer;
    }

    @NotNull
    public TopDownAnalysisContext analyzeDeclarations(
            @NotNull TopDownAnalysisMode topDownAnalysisMode,
            @NotNull Collection<? extends PsiElement> elements
    ) {
        TopDownAnalysisContext c = lazyTopDownAnalyzer.analyzeDeclarations(topDownAnalysisMode, elements, DataFlowInfoFactory.EMPTY);

        resolveImportsInAllFiles(c, codeAnalyzer);

        return c;
    }

    private static void resolveImportsInAllFiles(TopDownAnalysisContext c, KotlinCodeAnalyzer resolveSession) {
        for (KtFile file : c.getFiles()) {
            resolveAndCheckImports(file, resolveSession);
        }

        for (KtScript script : c.getScripts().keySet()) {
            resolveAndCheckImports(script.getContainingKtFile(), resolveSession);
        }
    }

    private static void resolveAndCheckImports(@NotNull KtFile file, @NotNull KotlinCodeAnalyzer resolveSession) {
        ImportResolver importResolver = resolveSession.getFileScopeProvider().getImportResolver(file);
        importResolver.forceResolveAllImports();
    }
}


