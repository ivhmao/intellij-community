// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.internal

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.codeInsight.lookup.impl.LookupImpl
import com.intellij.codeInsight.template.impl.LiveTemplateLookupElement
import com.intellij.diagnostic.ThreadDumper
import com.intellij.ide.DataManager
import com.intellij.internal.performance.TypingLatencyReportDialog
import com.intellij.internal.performance.latencyMap
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.actionSystem.ex.ActionManagerEx
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.actionSystem.LatencyRecorder
import com.intellij.openapi.editor.impl.EditorImpl
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.extensions.Extensions
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.util.Alarm

/**
 * @author yole
 */
class RetypeFileAction : AnAction() {
  override fun actionPerformed(e: AnActionEvent) {
    val editor = e.getData(CommonDataKeys.EDITOR) as? EditorImpl ?: return
    val project = e.getData(CommonDataKeys.PROJECT) ?: return
    val existingSession = editor.getUserData(RETYPE_SESSION_KEY)
    if (existingSession != null) {
      existingSession.stop()
    }
    else {
      val session = RetypeSession(project, editor, 400)
      editor.putUserData(RETYPE_SESSION_KEY, session)
      session.start()
    }
  }

  override fun update(e: AnActionEvent) {
    val editor = e.getData(CommonDataKeys.EDITOR)
    e.presentation.isEnabled = e.project != null && editor != null
    val retypeSession = editor?.getUserData(RETYPE_SESSION_KEY)
    if (retypeSession != null) {
      e.presentation.text = "Stop Retyping"
    }
    else {
      e.presentation.text = "Retype Current File"
    }
  }
}

interface RetypeFileAssistant {
  fun acceptLookupElement(element: LookupElement): Boolean

  companion object {
    val EP_NAME = ExtensionPointName.create<RetypeFileAssistant>("com.intellij.retypeFileAssistant")
  }
}

class RetypeSession(private val project: Project, private val editor: EditorImpl, private val delayMillis: Int) : Disposable {
  private val document = editor.document
  private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
  private val threadDumpAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
  private val originalText = editor.document.text
  private val lines = editor.document.text.split('\n').map { it + "\n" }
  private var line = -1
  private var column = 0
  private var completedChars = 0
  private val threadDumps = mutableListOf<String>()
  private val oldSelectAutopopup = CodeInsightSettings.getInstance().SELECT_AUTOPOPUP_SUGGESTIONS_BY_CHARS
  private var needSyncPosition = false
  private var editorLineBeforeAcceptingLookup = -1

  val currentLineText get() = lines[line]

  fun start() {
    latencyMap.clear()
    WriteCommandAction.runWriteCommandAction(project) { document.deleteString(0, document.textLength) }
    CodeInsightSettings.getInstance().SELECT_AUTOPOPUP_SUGGESTIONS_BY_CHARS = false
    queueNext()
  }

  fun stop() {
    WriteCommandAction.runWriteCommandAction(project) { document.replaceString(0, document.textLength, originalText) }
    Disposer.dispose(this)
    editor.putUserData(RETYPE_SESSION_KEY, null)
    CodeInsightSettings.getInstance().SELECT_AUTOPOPUP_SUGGESTIONS_BY_CHARS = oldSelectAutopopup
  }

  override fun dispose() {
  }

  private fun queueNext() {
    if (!alarm.isDisposed) {
      alarm.addRequest({ typeNext() }, delayMillis)
    }
  }

  private fun typeNext() {
    threadDumpAlarm.addRequest({ logThreadDump() }, 100)

    if (column == 0) {
      if (line >= 0) {
        if (checkPrevLineInSync()) return
      }
      line++
      syncPositionWithEditor()
    }
    else if (needSyncPosition) {
      val lineDelta = editor.caretModel.logicalPosition.line - editorLineBeforeAcceptingLookup
      if (lineDelta > 0) {
        line += lineDelta
        column = 0
      }
      syncPositionWithEditor()
    }
    needSyncPosition = false

    val lookup = LookupManager.getActiveLookup(editor) as LookupImpl?
    var lookupSelected = false
    if (lookup != null) {
      val lookupString = lookup.currentItem?.let { LookupElementPresentation.renderElement(it).itemText }
      val lookupStartColumn = editor.offsetToLogicalPosition(lookup.lookupStart).column
      if (lookupString != null && isLookupElementAcceptable(lookup.currentItem) &&
          currentLineText.drop(lookupStartColumn).take(lookupString.length) == lookupString) {
        lookup.focusDegree = LookupImpl.FocusDegree.FOCUSED
        editorLineBeforeAcceptingLookup = editor.caretModel.logicalPosition.line
        executeEditorAction(IdeActions.ACTION_CHOOSE_LOOKUP_ITEM)
        needSyncPosition = true
        lookupSelected = true
      }
    }

    if (!lookupSelected) {
      val c = currentLineText[column]
      if (c == '\n') {
        column = 0   // line will be incremented in next loop
        executeEditorAction(IdeActions.ACTION_EDITOR_ENTER)
      }
      else {
        column++
        editor.type(c.toString())
      }
    }
    if ((column == 0 && line < lines.size - 1) || (column > 0 && column < currentLineText.length)) {
      queueNext()
    }
    else {
      stop()
      TypingLatencyReportDialog(project, threadDumps).show()
    }
  }

  private fun isLookupElementAcceptable(lookupElement: LookupElement?): Boolean {
    if (lookupElement == null) return false
    for (retypeFileAssistant in Extensions.getExtensions(RetypeFileAssistant.EP_NAME)) {
      if (!retypeFileAssistant.acceptLookupElement(lookupElement)) {
        return false
      }
    }
    return lookupElement !is LiveTemplateLookupElement
  }

  private fun checkPrevLineInSync(): Boolean {
    val prevLine = getEditorLineText(editor.caretModel.logicalPosition.line - 1)
    if (prevLine.trimEnd() != currentLineText.trimEnd()) {
      Messages.showErrorDialog(project, "Text has diverged. Expected:\n$currentLineText\nActual:\n$prevLine", "Retype File")
      stop()
      return true
    }
    return false
  }

  private fun getEditorLineText(line: Int): String {
    val prevLineStart = document.getLineStartOffset(line)
    val prevLineEnd = document.getLineEndOffset(line)
    return document.text.substring(prevLineStart, prevLineEnd)
  }

  private fun syncPositionWithEditor(): Boolean {
    var result = false
    val editorLine = editor.caretModel.logicalPosition.line
    val editorLineText = getEditorLineText(editorLine)
    while (column < editorLineText.length && column < currentLineText.length && editorLineText[column] == currentLineText[column]) {
      result = true
      completedChars++
      column++
    }
    if (editor.caretModel.logicalPosition.column < column) {
      editor.caretModel.moveToLogicalPosition(LogicalPosition(line, column))
    }
    else if (editor.caretModel.logicalPosition.column > column) {
      // unwanted completion, backtrack
      println("Text has diverged, backtracking. Editor text:\n$editorLineText\nBuffer text:\n$currentLineText")
      WriteCommandAction.runWriteCommandAction(project) {
        editor.document.deleteString(document.getLineStartOffset(editorLine) + column, document.getLineEndOffset(editorLine))
      }
    }
    return result
  }

  private fun executeEditorAction(actionId: String) {
    val actionManager = ActionManagerEx.getInstanceEx()
    val action = actionManager.getAction(actionId)
    val event = AnActionEvent.createFromAnAction(action, null, "", DataManager.getInstance().getDataContext(editor.component))
    action.beforeActionPerformedUpdate(event)
    actionManager.fireBeforeActionPerformed(action, event.dataContext, event)
    LatencyRecorder.getInstance().recordLatencyAwareAction(editor, actionId, System.currentTimeMillis())
    action.actionPerformed(event)
    actionManager.fireAfterActionPerformed(action, event.dataContext, event)
  }

  private fun logThreadDump() {
    if (editor.isProcessingTypedAction) {
      threadDumps.add(ThreadDumper.dumpThreadsToString())
      threadDumpAlarm.addRequest({ logThreadDump() }, 100)
    }
  }

  companion object {
    val LOG = Logger.getInstance("#com.intellij.internal.RetypeSesssion")
  }
}

val RETYPE_SESSION_KEY = Key.create<RetypeSession>("com.intellij.internal.RetypeSession")
