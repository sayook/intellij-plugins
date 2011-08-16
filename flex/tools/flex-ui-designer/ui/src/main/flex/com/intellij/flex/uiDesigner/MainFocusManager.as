package com.intellij.flex.uiDesigner {
import cocoa.AbstractFocusManager;
import cocoa.FocusManager;
import cocoa.Focusable;
import cocoa.SegmentedControl;

import com.intellij.flex.uiDesigner.flex.DocumentFocusManagerSB;
import com.intellij.flex.uiDesigner.flex.MainFocusManagerSB;

import flash.display.DisplayObject;

import flash.display.DisplayObjectContainer;

import flash.display.InteractiveObject;
import flash.display.NativeWindow;
import flash.display.Shape;
import flash.display.Stage;
import flash.events.Event;
import flash.events.MouseEvent;

public class MainFocusManager extends AbstractFocusManager implements FocusManager, MainFocusManagerSB {
  override protected function mouseDownHandler(event:MouseEvent):void {
    if (_activeDocumentFocusManager != null && _activeDocumentFocusManager.handleMouseDown(event)) {
      lastFocus = null;
    }
    else {
      var target:InteractiveObject = InteractiveObject(event.target);
      var newFocus:Focusable = getTopLevelFocusTarget(target);
      if (newFocus != lastFocus && newFocus != null) {
        lastFocus = newFocus;
        target.stage.focus = newFocus.focusObject;
      }
    }

    //if (event.altKey) {
    //  var p:DisplayObjectContainer = InteractiveObject(event.target).stage;
    //  tC(p);
    //}
  }

  //private static function tC(p:DisplayObjectContainer):void {
  //  var childNumber:int = 0;
  //  while (true) {
  //    try {
  //      var child:DisplayObject = p.getChildAt(childNumber++);
  //      if (child is Shape) {
  //        var t:String = "33";
  //        t += "ss";
  //      }
  //      else if (child is DisplayObjectContainer && !(child is SegmentedControl)) {
  //        tC(DisplayObjectContainer(child));
  //      }
  //    }
  //    catch (e:RangeError) {
  //      break;
  //    }
  //  }
  //}

  private var _activeDocumentFocusManager:DocumentFocusManagerSB;
  public function set activeDocumentFocusManager(value:DocumentFocusManagerSB):void {
    _activeDocumentFocusManager = value;
    if (_activeDocumentFocusManager != null) {
      _activeDocumentFocusManager.restoreFocusToLastControl();
    }
  }

  override protected function windowActivateHandler(event:Event):void {
    var suggestedFocus:InteractiveObject;
    if (_activeDocumentFocusManager != null) {
      suggestedFocus = _activeDocumentFocusManager.restoreFocusToLastControl();
      if (suggestedFocus == null) {
        // activeDocumentFocusManager set focus to its object
        return;
      }
    }

    var stage:Stage = NativeWindow(event.currentTarget).stage;
    if (lastFocus != null) {
      stage.focus = lastFocus.focusObject;
    }
    else if (suggestedFocus != null) {
      stage.focus = suggestedFocus;
    }
  }
}
}
