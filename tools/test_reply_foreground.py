#!/usr/bin/env python3
"""Execute the production foreground ownership helper against minimal property-only Android stubs.
This verifies ownership/restoration, not NinePatch rendering or Android layout.
"""
from pathlib import Path
import subprocess
import tempfile
ROOT=Path(__file__).resolve().parents[1]
STUBS={
 'android/graphics/Color.java':'package android.graphics; public class Color { public static final int TRANSPARENT=0; }',
 'android/graphics/drawable/Drawable.java':'package android.graphics.drawable; public class Drawable {}',
 'android/graphics/drawable/NinePatchDrawable.java':'package android.graphics.drawable; public class NinePatchDrawable extends Drawable {}',
 'android/graphics/drawable/ColorDrawable.java':'package android.graphics.drawable; public class ColorDrawable extends Drawable { public ColorDrawable(int c) {} }',
 'android/view/View.java':'package android.view; import android.graphics.drawable.Drawable; public class View { private Drawable foreground; public int writes; public Drawable getForeground(){return foreground;} public void setForeground(Drawable d){foreground=d; writes++;} }',
 'com/waenhancer/xposed/features/customization/ReplyChecks.java':r'''
package com.waenhancer.xposed.features.customization;
import android.view.View;
import android.graphics.drawable.*;
import java.util.Set;
public class ReplyChecks {
 static void check(boolean v,String why){if(!v)throw new AssertionError(why);}
 public static void main(String[] args){
  for(String role:new String[]{"voice_note_draft_quoted_message_frame","composer_quoted_message_frame"}) {
   ReplyFrameForeground helper=new ReplyFrameForeground(); View frame=new View();
   Drawable a=new NinePatchDrawable(), b=new NinePatchDrawable(); frame.setForeground(a);
   helper.clear(frame,"message_bubble"); check(frame.getForeground()==a,"unrelated NinePatch untouched");
   helper.clear(frame,role); Drawable replacement=frame.getForeground();
   check(replacement instanceof ColorDrawable && helper.owns(frame),"exact frame fill owned");
   int writes=frame.writes; for(int i=0;i<100;i++)helper.clear(frame,role);
   check(frame.writes==writes,"same binding does not issue redundant setters");
   helper.retain(Set.of(frame)); check(frame.getForeground()==replacement,"live frame retained");
   helper.retain(Set.of()); check(frame.getForeground()==a,"outgoing frame restores exact native drawable");
   helper.clear(frame,role); frame.setForeground(b); helper.nativeAssignment(frame); helper.clear(frame,role);
   helper.retain(null); check(frame.getForeground()==b,"latest same-bounds native rebind restored");
   helper.clear(frame,role); frame.setForeground(a); helper.retain(null);
   check(frame.getForeground()==a,"native rebind wins even if no setter notification arrives");
   Drawable ripple=new Drawable(); frame.setForeground(ripple); helper.clear(frame,role);
   check(frame.getForeground()==ripple,"non-NinePatch states and ripple are untouched");
  }
  System.out.println("PASS: exact reply roles, idempotence, detach/disable restoration and native foreground replacement");
 }
}
'''}
with tempfile.TemporaryDirectory(prefix='waex-reply-') as directory:
 tmp=Path(directory); files=[]
 for path,content in STUBS.items():
  p=tmp/path;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content);files.append(p)
 files += [ROOT/'app/src/main/java/com/waenhancer/theme/OwnedProperty.java',ROOT/'app/src/main/java/com/waenhancer/xposed/features/customization/ReplyFrameForeground.java']
 subprocess.run(['java','com.sun.tools.javac.Main','-d',str(tmp),*map(str,files)],check=True)
 subprocess.run(['java','-XX:-UsePerfData','-cp',str(tmp),'com.waenhancer.xposed.features.customization.ReplyChecks'],check=True)
