/***
 * Copyright 2013 Teoti Graphix, LLC.
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
 *
 *
 * @author Michael Schmalle <mschmalle@teotigraphix.com>
 */

package randori.plugin.components;

import com.intellij.analysis.AnalysisScopeBundle;
import com.intellij.codeInsight.actions.OptimizeImportsProcessor;
import com.intellij.codeInsight.actions.ReformatCodeAction;
import com.intellij.compiler.impl.ModuleCompileScope;
import com.intellij.lang.Language;
import com.intellij.lang.javascript.ActionScriptFileType;
import com.intellij.lang.javascript.flex.ImportUtils;
import com.intellij.lang.javascript.psi.JSFile;
import com.intellij.lang.javascript.psi.ecmal4.JSAttribute;
import com.intellij.lang.javascript.psi.ecmal4.impl.JSAttributeListImpl;
import com.intellij.openapi.application.AccessToken;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.compiler.CompilerManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.util.io.FileUtilRt;
import com.intellij.openapi.vfs.*;
import com.intellij.psi.FileViewProvider;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import randori.plugin.configuration.RandoriCompilerModel;
import randori.plugin.util.ProjectUtils;

import java.util.*;

/**
 * @author Michael Schmalle
 * @author Frédéric THOMAS
 */
class FileChangeListener implements VirtualFileListener
{
    private final Project project;

    public FileChangeListener(Project project)
    {
        this.project = project;
    }

    void validateAndParse(final VirtualFileEvent event, final boolean add, final boolean remove)
    {
        final VirtualFile file = event.getFile();
        final boolean isActionScriptFile = FileUtilRt.extensionEquals(file.getPath(),
                ActionScriptFileType.INSTANCE.getDefaultExtension());
        final boolean isBelongModule = ModuleUtilCore.findModuleForFile(file, project) != null;

        if (project == ProjectUtils.getProject() && isActionScriptFile && isBelongModule)
        {
            List<VirtualFile> modifiedFiles;
            final RandoriProjectComponent projectComponent = project.getComponent(RandoriProjectComponent.class);
            modifiedFiles = projectComponent.getModifiedFiles();

            if (modifiedFiles == null)
                return;

            if (add && !modifiedFiles.contains(file))
                modifiedFiles.add(file);

            if (remove && modifiedFiles.contains(file))
                modifiedFiles.remove(file);

            final ReadonlyStatusHandler.OperationStatus operationStatus = ReadonlyStatusHandler.getInstance(project)
                    .ensureFilesWritable(modifiedFiles);

            if (!operationStatus.hasReadonlyFiles())
                new OptimizeImportsProcessor(project, ReformatCodeAction.convertToPsiFiles(
                        modifiedFiles.toArray(new VirtualFile[modifiedFiles.size()]), project), new Runnable() {

                    @Override
                    public void run()
                    {
                        importAnnotations(file);

                        RandoriCompilerModel state = RandoriCompilerModel.getInstance(project).getState();

                        if (state != null && event.isFromSave() && state.isMakeOnSave())
                            executeMake(event);
                    }
                }).run();
        }
    }

    private void importAnnotations(@NotNull VirtualFile file)
    {
        final List<PsiElement> annotations = new ArrayList<PsiElement>();

        PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
        if (psiFile == null)
            return;

        FileViewProvider viewProvider = psiFile.getViewProvider();
        Set<Language> languages = viewProvider.getLanguages();
        JSFile asFile = (JSFile) viewProvider.getPsi(languages.iterator().next());

        for (PsiElement psiElement : asFile.getChildren())
        {
            findAnnotations(annotations, psiElement);
        }

        Map<JSAttribute, String> importStatements = getImportStatementsFromAnnotations(annotations);

        if (importStatements.size() > 0)
        {
            AccessToken accessToken = ApplicationManager.getApplication().acquireWriteActionLock(
                    FileChangeListener.class);
            try
            {
                for (Map.Entry<JSAttribute, String> entry : importStatements.entrySet())
                {
                    ImportUtils.doImport(entry.getKey(), entry.getValue(), true);
                }
            }
            finally
            {
                accessToken.finish();
            }
        }
    }

    private static void findAnnotations(List<PsiElement> annotations, @NotNull PsiElement aChild)
    {
        if (aChild instanceof JSAttributeListImpl)
        {
            for (PsiElement psiElement : aChild.getChildren())
            {
                if (psiElement instanceof JSAttribute && !annotations.contains(psiElement))
                {
                    annotations.add(psiElement);
                }
            }
        }

        for (PsiElement child : aChild.getChildren())
        {
            findAnnotations(annotations, child);
        }
    }

    private Map<JSAttribute, String> getImportStatementsFromAnnotations(List<PsiElement> annotations)
    {

        Map<JSAttribute, String> importStatements = new HashMap<JSAttribute, String>();

        //AnnotationManager annotationManager = new AnnotationManager(projectComponent.getCompiler());
        //TODO implement and maybe keep a map at class level and don't try to get what is already in the map.

        return importStatements;
    }

    private void executeMake(final VirtualFileEvent event)
    {
        ProgressManager.getInstance()
                .run(new Task.Backgroundable(project, AnalysisScopeBundle.message("analyzing.project", new Object[0]),
                        true) {
                    public void run(@NotNull ProgressIndicator indicator)
                    {
                        FileChangeListener.this.executeMakeInUIThread(event);
                    }
                });
    }

    @SuppressWarnings("unused")
    private void executeMakeInUIThread(VirtualFileEvent event)
    {
        if ((project.isInitialized()) && (!project.isDisposed()) && (project.isOpen()) && (!project.isDefault()))
        {
            final ModuleManager moduleManager = ModuleManager.getInstance(project);
            final CompilerManager compilerManager = CompilerManager.getInstance(project);

            UIUtil.invokeAndWaitIfNeeded(new Runnable() {
                public void run()
                {
                    final Module rootModule = moduleManager.findModuleByName(project.getName());

                    if (rootModule != null) {
                        if ((!compilerManager.isCompilationActive())
                                && (!compilerManager.isUpToDate(new ModuleCompileScope(rootModule, true))))
                            compilerManager.make(project, moduleManager.getModules(), null);
                    }
                }
            });
        }
    }

    @Override
    public void propertyChanged(VirtualFilePropertyEvent event)
    {
        if (event.getPropertyName().equals(VirtualFile.PROP_NAME)
                && VirtualFile.isValidName(event.getNewValue().toString()))
            validateAndParse(event, true, false);
    }

    @Override
    public void contentsChanged(VirtualFileEvent event)
    {
        validateAndParse(event, true, false);
    }

    @Override
    public void fileCreated(VirtualFileEvent event)
    {
        validateAndParse(event, true, false);
    }

    @Override
    public void fileDeleted(VirtualFileEvent event)
    {
        validateAndParse(event, false, true);
    }

    @Override
    public void fileMoved(VirtualFileMoveEvent event)
    {
        validateAndParse(event, false, false);
    }

    @Override
    public void fileCopied(VirtualFileCopyEvent event)
    {
        validateAndParse(event, true, false);
    }

    @Override
    public void beforePropertyChange(VirtualFilePropertyEvent event)
    {
    }

    @Override
    public void beforeContentsChange(VirtualFileEvent event)
    {
    }

    @Override
    public void beforeFileDeletion(VirtualFileEvent event)
    {
    }

    @Override
    public void beforeFileMovement(VirtualFileMoveEvent event)
    {
    }
}