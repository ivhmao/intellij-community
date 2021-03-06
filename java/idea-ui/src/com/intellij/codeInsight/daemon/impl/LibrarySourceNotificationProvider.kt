/*
 * Copyright 2000-2017 JetBrains s.r.o.
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
package com.intellij.codeInsight.daemon.impl

import com.intellij.ProjectTopics
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.ide.util.PsiNavigationSupport
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectBundle
import com.intellij.openapi.roots.ModuleRootEvent
import com.intellij.openapi.roots.ModuleRootListener
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.impl.source.PsiExtensibleClass
import com.intellij.psi.util.PsiFormatUtil
import com.intellij.psi.util.PsiFormatUtilBase
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotifications
import com.intellij.ui.LightColors

class LibrarySourceNotificationProvider(private val project: Project, notifications: EditorNotifications) :
    EditorNotifications.Provider<EditorNotificationPanel>() {

  private companion object {
    private val KEY = Key.create<EditorNotificationPanel>("library.source.mismatch.panel")
    private val ANDROID_SDK_PATTERN = ".*/platforms/android-\\d+/android.jar!/.*".toRegex()
  }

  init {
    project.messageBus.connect(project).subscribe(ProjectTopics.PROJECT_ROOTS, object : ModuleRootListener {
      override fun rootsChanged(event: ModuleRootEvent) = notifications.updateAllNotifications()
    })
  }

  override fun getKey(): Key<EditorNotificationPanel> = KEY

  override fun createNotificationPanel(file: VirtualFile, fileEditor: FileEditor): EditorNotificationPanel? {
    if (file.fileType is LanguageFileType && ProjectRootManager.getInstance(project).fileIndex.isInLibrarySource(file)) {
      val psiFile = PsiManager.getInstance(project).findFile(file)
      if (psiFile is PsiJavaFile) {
        val offender = psiFile.classes.find { differs(it) }
        if (offender != null) {
          val clsFile = offender.originalElement.containingFile?.virtualFile
          if (clsFile != null && !clsFile.path.matches(ANDROID_SDK_PATTERN)) {
            val panel = EditorNotificationPanel(LightColors.RED)
            panel.setText(ProjectBundle.message("library.source.mismatch", offender.name))
            panel.createActionLabel(ProjectBundle.message("library.source.open.class"), {
              if (!project.isDisposed && clsFile.isValid) {
                PsiNavigationSupport.getInstance().createNavigatable(project, clsFile, -1).navigate(true)
              }
            })
            panel.createActionLabel(ProjectBundle.message("library.source.show.diff"), {
              if (!project.isDisposed && clsFile.isValid) {
                val cf = DiffContentFactory.getInstance()
                val request = SimpleDiffRequest(null, cf.create(project, clsFile), cf.create(project, file), clsFile.path, file.path)
                DiffManager.getInstance().showDiff(project, request)
              }
            })
            return panel
          }
        }
      }
    }

    return null
  }

  private fun differs(clazz: PsiClass): Boolean {
    val binary = clazz.originalElement
    return binary !== clazz &&
        binary is PsiClass &&
           (fieldDiffers(fields(clazz), fields(binary)) || methodsDiffers(methods(clazz), methods(binary)) || classDiffers(inners(clazz), inners(binary)))
  }

  private fun classDiffers(list1: List<PsiClass>, list2: List<PsiClass>): Boolean {
    val classOptions = PsiFormatUtil.SHOW_NAME + PsiFormatUtil.SHOW_FQ_CLASS_NAMES + PsiFormatUtil.SHOW_EXTENDS_IMPLEMENTS
    return list1.size != list2.size || list1.map { PsiFormatUtil.formatClass(it, classOptions) }.sorted() !=
                                       list2.map { PsiFormatUtil.formatClass(it, classOptions) }.sorted()
  }
  
  private fun fieldDiffers(list1: List<PsiField>, list2: List<PsiField>): Boolean {
    val fieldOptions = PsiFormatUtil.SHOW_NAME + PsiFormatUtil.SHOW_TYPE + PsiFormatUtil.SHOW_FQ_CLASS_NAMES 
    return list1.size != list2.size || list1.map { PsiFormatUtil.formatVariable(it, fieldOptions, PsiSubstitutor.EMPTY) }.sorted() != 
                                       list2.map { PsiFormatUtil.formatVariable(it, fieldOptions, PsiSubstitutor.EMPTY) }.sorted()
  }

  private fun methodsDiffers(list1: List<PsiMethod>, list2: List<PsiMethod>): Boolean {
    val methodOptions = PsiFormatUtil.SHOW_NAME + PsiFormatUtilBase.SHOW_PARAMETERS
    val parametersOptions = PsiFormatUtilBase.SHOW_TYPE + PsiFormatUtil.SHOW_FQ_CLASS_NAMES
    return list1.size != list2.size || 
           list1.map { PsiFormatUtil.formatMethod(it, PsiSubstitutor.EMPTY, methodOptions, parametersOptions) }.sorted() !=
           list2.map { PsiFormatUtil.formatMethod(it, PsiSubstitutor.EMPTY, methodOptions, parametersOptions) }.sorted()
  }

  private fun fields(clazz: PsiClass) = (clazz as? PsiExtensibleClass)?.ownFields ?: clazz.fields.asList()
  private fun methods(clazz: PsiClass): List<PsiMethod> =
      ((clazz as? PsiExtensibleClass)?.ownMethods ?: clazz.methods.asList())
          .filter { !(it.isConstructor && it.parameterList.parametersCount == 0) }
  private fun inners(clazz: PsiClass) = (clazz as? PsiExtensibleClass)?.ownInnerClasses ?: clazz.innerClasses.asList()
}
