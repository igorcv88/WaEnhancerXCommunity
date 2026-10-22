#!/usr/bin/env python3
"""Compile real entrypoint/IPC classes against small host stubs and exercise negative paths.

Stubs test dispatch, races and authentication; Android package visibility is a device test.
"""
import pathlib
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[1]
JAVA = ROOT / 'app/src/main/java'
STUBS = {
'android/content/SharedPreferences.java': '''package android.content; public interface SharedPreferences { Editor edit(); interface Editor { Editor putInt(String k,int v); void apply(); } }''',
'android/content/Context.java': '''package android.content; public class Context { public static int MODE_PRIVATE=0; public ContentResolver resolver=new ContentResolver(); public android.content.pm.PackageManager pm=new android.content.pm.PackageManager(); public Context getApplicationContext(){return this;} public ContentResolver getContentResolver(){return resolver;} public android.content.pm.PackageManager getPackageManager(){return pm;} public SharedPreferences getSharedPreferences(String n,int m){return null;} }''',
'android/content/ContentResolver.java': '''package android.content; public class ContentResolver { public volatile int calls; public volatile java.util.concurrent.CountDownLatch latch; public android.os.Bundle call(android.net.Uri u,String m,String a,android.os.Bundle b){ calls++; if(!u.toString().equals("content://com.waenhancer.community.hookprovider"))throw new AssertionError(u); try {if(latch!=null)latch.await();}catch(Exception e){throw new RuntimeException(e);} android.os.Bundle r=new android.os.Bundle(); r.putBinder("binder",new com.waenhancer.xposed.bridge.WaeIIFace.Stub(){});return r;} }''',
'android/content/pm/PackageManager.java': '''package android.content.pm; public class PackageManager { public String[] owned; public String[] getPackagesForUid(int uid){return owned;} public ApplicationInfo getApplicationInfo(String p,int f)throws NameNotFoundException{return new ApplicationInfo();} public static class NameNotFoundException extends Exception{} }''',
'android/content/pm/ApplicationInfo.java': '''package android.content.pm; public class ApplicationInfo {public String sourceDir="host.apk";public int uid;}''',
'android/app/Application.java': '''package android.app; public class Application extends android.content.Context {}''',
'android/content/res/XModuleResources.java': '''package android.content.res; public class XModuleResources {public static Object createInstance(String path,Object r){return new Object();}}''',
'android/view/Window.java': '''package android.view; public class Window {}''',
'android/view/WindowManager.java': '''package android.view; public class WindowManager {public static class LayoutParams {public static int FLAG_SECURE=8192;}}''',
'android/net/Uri.java': '''package android.net; public class Uri {String value; public static Uri parse(String s){Uri u=new Uri();u.value=s;return u;}public String toString(){return value;}}''',
'android/os/IBinder.java': '''package android.os; public interface IBinder {boolean pingBinder();}''',
'android/os/Bundle.java': '''package android.os; public class Bundle {IBinder binder;public void putBinder(String k,IBinder b){binder=b;}public IBinder getBinder(String k){return binder;}}''',
'android/os/Binder.java': '''package android.os; public class Binder { public static int uid=2000; public static int getCallingUid(){return uid;} }''',
'android/os/Process.java': '''package android.os; public class Process {public static int myUid(){return 12345;}}''',
'android/os/RemoteException.java': '''package android.os; public class RemoteException extends Exception {}''',
'android/os/ParcelFileDescriptor.java': '''package android.os; public class ParcelFileDescriptor {public static int MODE_READ_WRITE=3;public static ParcelFileDescriptor open(java.io.File f,int m)throws java.io.FileNotFoundException {return new ParcelFileDescriptor();}}''',
'com/waenhancer/BuildConfig.java': '''package com.waenhancer; public class BuildConfig {public static final String APPLICATION_ID="com.waenhancer.community";}''',
'com/waenhancer/R.java': '''package com.waenhancer; public class R {}''',
'com/waenhancer/App.java': '''package com.waenhancer; public class App {public static boolean isOriginalPackage(){return true;}}''',
'com/waenhancer/config/NspPreferenceMigration.java': '''package com.waenhancer.config; public class NspPreferenceMigration {public static void restoreIfNeeded(android.content.Context c){}}''',
'com/waenhancer/xposed/utils/Utils.java': '''package com.waenhancer.xposed.utils; public class Utils {public static boolean DEBUG=false;static{de.robv.android.xposed.XposedBridge.helpers++;}}''',
'com/waenhancer/xposed/utils/XResManager.java': '''package com.waenhancer.xposed.utils; public class XResManager {public static Object moduleResources,hostResources;public static java.util.Set<Integer> validModuleIds=new java.util.HashSet<>();public static int getHostId(int i){return i;}}''',
'com/waenhancer/xposed/bridge/client/DeferredProviderSharedPreferences.java': '''package com.waenhancer.xposed.bridge.client; public class DeferredProviderSharedPreferences {}''',
'com/waenhancer/xposed/core/FeatureLoader.java': '''package com.waenhancer.xposed.core; public class FeatureLoader {public static final String PACKAGE_WPP="com.whatsapp",PACKAGE_BUSINESS="com.whatsapp.w4b";public static int starts;public static void start(ClassLoader l,Object p,String path){starts++;}}''',
'com/waenhancer/xposed/bridge/WaeIIFace.java': '''package com.waenhancer.xposed.bridge; public interface WaeIIFace {android.os.IBinder asBinder(); abstract class Stub implements WaeIIFace,android.os.IBinder {public static WaeIIFace asInterface(android.os.IBinder b){return (WaeIIFace)b;} public android.os.IBinder asBinder(){return this;}public boolean pingBinder(){return true;} public android.os.ParcelFileDescriptor openFile(String p,boolean c)throws android.os.RemoteException{return null;} public boolean createDir(String p)throws android.os.RemoteException{return false;}public java.util.List listFiles(String p)throws android.os.RemoteException{return null;}}}''',
'de/robv/android/xposed/XposedBridge.java': '''package de.robv.android.xposed;public class XposedBridge {public static int hooks,helpers;public static void log(Object m){}public static int getXposedVersion(){return 100;}}''',
'de/robv/android/xposed/XposedHelpers.java': '''package de.robv.android.xposed;public class XposedHelpers {public static void findAndHookMethod(Object...args){XposedBridge.hooks++;}}''',
'de/robv/android/xposed/XC_MethodHook.java': '''package de.robv.android.xposed;public class XC_MethodHook {protected void beforeHookedMethod(MethodHookParam p)throws Throwable{}public static class MethodHookParam {public Object thisObject;public Object[] args;public void setResult(Object r){}}}''',
'de/robv/android/xposed/XC_MethodReplacement.java': '''package de.robv.android.xposed;public class XC_MethodReplacement extends XC_MethodHook {public static Object returnConstant(Object v){return null;}}''',
'de/robv/android/xposed/IXposedHookLoadPackage.java': '''package de.robv.android.xposed;public interface IXposedHookLoadPackage {void handleLoadPackage(de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam p)throws Throwable;}''',
'de/robv/android/xposed/IXposedHookInitPackageResources.java': '''package de.robv.android.xposed;public interface IXposedHookInitPackageResources {void handleInitPackageResources(de.robv.android.xposed.callbacks.XC_InitPackageResources.InitPackageResourcesParam p)throws Throwable;}''',
'de/robv/android/xposed/IXposedHookZygoteInit.java': '''package de.robv.android.xposed;public interface IXposedHookZygoteInit {void initZygote(StartupParam p)throws Throwable;class StartupParam {public String modulePath;}}''',
'de/robv/android/xposed/callbacks/XC_LoadPackage.java': '''package de.robv.android.xposed.callbacks;public class XC_LoadPackage {public static class LoadPackageParam {public String packageName,processName;public ClassLoader classLoader;public android.content.pm.ApplicationInfo appInfo=new android.content.pm.ApplicationInfo();}}''',
'de/robv/android/xposed/callbacks/XC_InitPackageResources.java': '''package de.robv.android.xposed.callbacks;public class XC_InitPackageResources {public static class InitPackageResourcesParam {public String packageName;public Object res;}}''',
'Harness.java': '''import com.waenhancer.*; import de.robv.android.xposed.*;
public class Harness {
 static void check(boolean ok){if(!ok)throw new AssertionError();}
 static void denied(Runnable r){try{r.run();throw new AssertionError("accepted attacker");}catch(SecurityException expected){}}
 public static void main(String[]args)throws Throwable {
  WppXposed e=new WppXposed();e.initZygote(new IXposedHookZygoteInit.StartupParam());
  for(String p:new String[]{"android","system","com.android.providers.settings","com.google.android.gms","com.android.vending","com.google.android.apps.walletnfcrel",null}){
   var lp=new de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam();lp.packageName=p;lp.processName="android";e.handleLoadPackage(lp);
   var rp=new de.robv.android.xposed.callbacks.XC_InitPackageResources.InitPackageResourcesParam();rp.packageName=p;if(p!=null)e.handleInitPackageResources(rp);
  }
  check(XposedBridge.hooks==0 && XposedBridge.helpers==0);
  for(String p:new String[]{"com.whatsapp","com.whatsapp.w4b",BuildConfig.APPLICATION_ID}){var lp=new de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam();lp.packageName=p;e.handleLoadPackage(lp);}
  check(com.waenhancer.xposed.core.FeatureLoader.starts==2);check(XposedBridge.hooks==6);
  android.content.Context c=new android.content.Context();var binder=com.waenhancer.xposed.bridge.service.HookBinder.getInstance(c);
  for(int uid:new int[]{0,1000,2000,9999}){android.os.Binder.uid=uid;c.pm.owned=new String[]{"attacker"};
   denied(()->{try{binder.createDir("/unused");}catch(android.os.RemoteException ex){throw new RuntimeException(ex);}});
   denied(()->{try{binder.openFile("/unused",false);}catch(android.os.RemoteException ex){throw new RuntimeException(ex);}});
   denied(()->{try{binder.listFiles("/unused");}catch(android.os.RemoteException ex){throw new RuntimeException(ex);}});
  }
  for(String p:new String[]{"com.whatsapp","com.whatsapp.w4b"}){c.pm.owned=new String[]{p};android.os.Binder.uid=10001;check(binder.listFiles("/absent")!=null);}
  c.pm.owned=null;android.os.Binder.uid=android.os.Process.myUid();check(binder.listFiles("/absent")!=null);
  c.pm.owned=null;android.os.Binder.uid=10001;denied(()->{try{binder.listFiles("/absent");}catch(android.os.RemoteException ex){throw new RuntimeException(ex);}});
  var client=new com.waenhancer.xposed.bridge.client.ProviderClient(c);check(client.connect().get());check(c.resolver.calls==1);check(client.connect().get());check(c.resolver.calls==1);
  android.content.Context blocked=new android.content.Context();blocked.resolver.latch=new java.util.concurrent.CountDownLatch(1);
  var late=new com.waenhancer.xposed.bridge.client.ProviderClient(blocked);var pending=late.connect();check(pending==late.connect());check(!pending.get(5,java.util.concurrent.TimeUnit.SECONDS));
  blocked.resolver.latch.countDown();Thread.sleep(100);check(late.getService()==null);check(late.connect().get());
  System.out.println("PASS: rejected scopes register zero hooks; WA/Business/self dispatch; Binder UID rejection; direct provider; shared connection; timeout rejects late Binder; reconnect");
 }
}'''
}


class Runtime(unittest.TestCase):
    def test_actual_entrypoint_and_ipc(self):
        with tempfile.TemporaryDirectory() as td:
            tmp=pathlib.Path(td)
            files=[]
            for name,source in STUBS.items():
                p=tmp/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(source);files.append(str(p))
            for name in ('AppOnlyScope.java','WppXposed.java','security/CallerAuthority.java',
                         'xposed/bridge/service/HookBinder.java','xposed/bridge/client/BaseClient.java',
                         'xposed/bridge/client/ProviderClient.java'):
                files.append(str(JAVA/'com/waenhancer'/name))
            subprocess.run(['java','com.sun.tools.javac.Main','-d',str(tmp/'classes'),*files],check=True)
            subprocess.run(['java','-cp',str(tmp/'classes'),'Harness'],check=True,timeout=15)

    def test_all_resource_qualifiers_and_manifest(self):
        found=0
        for p in (ROOT/'app/src').rglob('*.xml'):
            xml=ET.parse(p)
            for array in xml.findall(".//string-array[@name='scope']"):
                self.assertEqual({'com.whatsapp','com.whatsapp.w4b','com.waenhancer.community'}, {e.text for e in array.findall('item')},str(p));found+=1
        self.assertGreater(found,0)
        ns='{http://schemas.android.com/apk/res/android}'
        app=ET.parse(ROOT/'app/src/main/AndroidManifest.xml').find('application')
        self.assertEqual('true',app.get(ns+'forceQueryable'))
        self.assertFalse(any('BridgeService' in e.get(ns+'name','') for e in app.findall('service')))

    def test_removed_implementations_and_zygote_markers(self):
        s=(JAVA/'com/waenhancer/WppXposed.java').read_text()
        z=s[s.index('public void initZygote'):s.index('public void disableSecureFlag')]
        self.assertNotIn('SystemProperties',z)
        for name in ('ScopeHook','AntiUpdater','HookBL','BridgeClient','BridgeService','AndroidPermissions'):
            self.assertFalse(list(JAVA.rglob(name+'.java')),name)
        self.assertFalse((JAVA/'com/waenhancer/xposed/downgrade/Patch.java').exists())
        provider=(JAVA/'com/waenhancer/xposed/bridge/providers/HookProvider.java').read_text()
        self.assertLess(provider.index('CallerAuthority.isTrustedCaller'),provider.index('long token = android.os.Binder.clearCallingIdentity'))


if __name__=='__main__':unittest.main(verbosity=2)
