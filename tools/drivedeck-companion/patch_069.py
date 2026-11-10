#!/usr/bin/env python3
"""Exact, fail-closed adaptation of official DriveDeck 0.6.9 code331.
The 0.6.8 adapter stays separately reproducible. No device operations.
"""
import pathlib, re, sys, xml.etree.ElementTree as ET, json
from app_update_patch_069 import apply as disable_stock_app_updates
root = pathlib.Path(sys.argv[1])
changes=[]
def edit(rel, fn):
    p=root/rel
    old=p.read_text(); new=fn(old)
    assert new != old, 'No change: '+rel
    p.write_text(new)
    changes.append(rel)
def method(rel, signature, body):
    def patch(s):
        pattern=r'(?ms)^\.method '+re.escape(signature)+r'\n.*?^\.end method'
        result,count=re.subn(pattern, lambda m: '.method '+signature+'\n'+body.strip()+'\n.end method',s)
        assert count==1, (rel,signature,count)
        return result
    edit(rel,patch)
void='    .locals 0\n    return-void'
false='    .locals 1\n    const/4 v0, 0x0\n    return v0'
main='smali/ru/drivedeck/launcher/MainActivity.smali'
# Android 13: let native controls accept focus before their original event dispatch.
def release_child_focus_for_host_touch(s):
    signature = '.method public final dispatchTouchEvent(Landroid/view/MotionEvent;)Z\n    .locals 19\n'
    assert s.count(signature) == 1
    return s.replace(signature, signature + '\n    invoke-static/range {p0 .. p1}, Lru/drivedeck/headswap/EmbeddedDisplayClient;->beforeHostTouch(Landroid/app/Activity;Landroid/view/MotionEvent;)V\n')
edit(main, release_child_focus_for_host_touch)
# Completely remove home-role requests, global theme sync, and global-permission setup.
for sig in ['public final h0()V','public final B0()V','public final w0()V','public final Y(I)V']:
    method(main,sig,void)
# Display routing must not pretend the APK has system UID.
method('smali/c/a9.1.smali','public static w(Landroid/content/Context;Ljava/lang/String;ILc/g8;ZZ)Z', '\n'.join([
    '    .locals 1',
    '    if-eqz p3, :headswap_parent_launch',
    '    invoke-static {p2, p1}, Lru/drivedeck/headswap/EmbeddedDisplayClient;->launch(ILjava/lang/String;)Z',
    '    move-result v0', '    return v0',
    '    :headswap_parent_launch',
    '    if-nez p2, :headswap_rejected_launch',
    '    invoke-static {p0, p1}, Lru/drivedeck/headswap/DisplayOnlyLaunch;->launch(Landroid/content/Context;Ljava/lang/String;)Z',
    '    move-result v0', '    return v0',
    '    :headswap_rejected_launch', '    const/4 v0, 0x0', '    return v0']))
for name,top in [('R',0),('p',1)]:
    method('smali/c/a9.1.smali',f'public static {name}(Landroid/content/Context;Ljava/lang/String;I)I',f"    .locals 1\n    const/4 v0, 0x{top}\n    invoke-static {{p2, p1, v0}}, Lru/drivedeck/headswap/EmbeddedDisplayClient;->taskState(ILjava/lang/String;Z)I\n    move-result v0\n    return v0")
method('smali/c/a9.1.smali','public static s(ILandroid/view/InputEvent;)Z', '    .locals 1\n    invoke-static {p0, p1}, Lru/drivedeck/headswap/EmbeddedDisplayClient;->input(ILandroid/view/InputEvent;)Z\n    move-result v0\n    return v0')
method('smali/c/a9.1.smali','public static l(I)V',void)
method('smali/c/a9.1.smali','public static K(ILandroid/view/MotionEvent;)V', '    .locals 0\n    invoke-static {p0, p1}, Lru/drivedeck/headswap/EmbeddedDisplayClient;->input(ILandroid/view/InputEvent;)Z\n    return-void')

# No root probes or root command execution in this ordinary-UID companion.
method('smali/c/a9.1.smali','public static C([Ljava/lang/String;)Z', false)
method('smali/c/a9.1.smali','public static e(Ljava/lang/String;)V', void)
method('smali/c/a9.1.smali','public static j()V', void)
# Fullscreen dashboard mode only; embedded split windows need original system privileges.
# No fallback navigator or renderer-created virtual display in dashboard mode.
method('smali/c/q4.1.smali','public final A()Ljava/lang/String;', '    .locals 1\n    iget-object v0, p0, Lc/q4;->F:Lc/d5;\n    invoke-virtual {v0}, Lc/d5;->t()Ljava/lang/String;\n    move-result-object v0\n    return-object v0')
renderer='smali/c/g8.1.smali'
# All companion renderers use the existing TextureView producer gateway; never ActivityView.
def owned_renderer_backend(s):
    old = '    move-object/from16 v2, p2\n'
    constructor = re.search(r'(?ms)^\.method public constructor <init>.*?^\.end method', s)
    assert constructor and constructor.group().count(old) == 1
    return s[:constructor.start()] + constructor.group().replace(old, '    const-string v2, "texture"\n', 1) + s[constructor.end():]
edit(renderer, owned_renderer_backend)
def keep_child_focus_for_renderer_touch(s):
    signature = '.method public final m(Landroid/view/MotionEvent;)Z\n    .locals 9\n'
    start = s.index(signature)
    end = s.index('.end method', start)
    entry = '    :cond_1\n    invoke-virtual {p1}, Landroid/view/MotionEvent;->getActionMasked()I'
    assert s[start:end].count(entry) == 1
    body = s[start:end].replace(entry, '    :cond_1\n    invoke-static/range {p0 .. p1}, Lru/drivedeck/headswap/EmbeddedDisplayClient;->onChildTouch(Landroid/view/View;Landroid/view/MotionEvent;)V\n    invoke-virtual {p1}, Landroid/view/MotionEvent;->getActionMasked()I')
    return s[:start] + body + s[end:]
edit(renderer, keep_child_focus_for_renderer_touch)
method(renderer,'public final c(Landroid/graphics/SurfaceTexture;II)V', '    .locals 0\n    invoke-static {p0, p1, p2, p3}, Lru/drivedeck/headswap/EmbeddedDisplayClient;->attach(Landroid/view/View;Landroid/graphics/SurfaceTexture;II)V\n    return-void')
method(renderer,'public final i()I', '    .locals 1\n    invoke-static {p0}, Lru/drivedeck/headswap/EmbeddedDisplayClient;->handle(Landroid/view/View;)I\n    move-result v0\n    return v0')
method(renderer,'public final h()I', '    .locals 1\n    invoke-static {p0}, Lru/drivedeck/headswap/EmbeddedDisplayClient;->density(Landroid/view/View;)I\n    move-result v0\n    return v0')
for name in ['x','r']:
    method(renderer,f'public final {name}(II)V', '    .locals 0\n    invoke-static {p0, p1, p2}, Lru/drivedeck/headswap/EmbeddedDisplayClient;->resize(Landroid/view/View;II)V\n    return-void')
method(renderer,'public final y(I)V', '    .locals 0\n    invoke-static {p0, p1}, Lru/drivedeck/headswap/EmbeddedDisplayClient;->scale(Landroid/view/View;I)V\n    return-void')

# Hardware controls, layout and unrelated widget settings stay intact. Only replace the three
# app-scale panels when the connected HeadSwap version supplies the authoritative display DPI.
def scale_settings(s):
    def patch_page(source, signature, label, controls, hint, pair=False):
        pattern = r'(?ms)^\.method public final '+re.escape(signature)+r'\n.*?^\.end method'
        m = re.search(pattern, source)
        assert m, signature
        body = m.group()
        encoded = label.encode('unicode_escape').decode()
        i = body.index('"'+encoded+'"')
        if pair:
            # Hold the group with the two percent sliders in v2 until its hint is built. The
            # register is dead at this point and assigned anew before its next original use.
            at = body[:i].rindex('    invoke-virtual {v3, v1}, Landroid/view/ViewGroup;->addView(Landroid/view/View;)V')
            end = body.index('\n', at)
            body = body[:end]+'\n    move-object v2, v1'+body[end:]
            i = body.index('"'+encoded+'"')
        add = re.search(r'    invoke-virtual \{\w+, '+hint+r'\}, Landroid/view/ViewGroup;->addView\(Landroid/view/View;\)V', body[i:])
        assert add, (signature, label)
        at = i+add.end()
        call = '\n    invoke-static {'+controls+', '+hint+'}, Lru/drivedeck/headswap/EmbeddedDisplayClient;->scaleSettings(Landroid/view/ViewGroup;Landroid/widget/TextView;)V'
        return source[:m.start()]+body[:at]+call+body[at:]+source[m.end():]
    if re.search(r'(?m)^\.class [^\n]+ Lc/H3;', s):
        s = patch_page(s,'Y()V','50–120%. Масштаб сохраняется отдельно для каждого приложения.','v3','v2')
        return patch_page(s,'b0()V','Масштаб меняет размер содержимого выбранной пары. 100% — обычный размер.','v2','v1', True)
    return patch_page(s,'U(I)V','Масштаб недоступен для системного окна.','v10','v0')
edit('smali/c/H3.smali', scale_settings)
edit('smali/c/q4.1.smali', scale_settings)

# The regular dock/app-drawer path is MainActivity.U -> R8.w with a null embedded renderer.
# The media widget has another standalone path. Keep its Activity context to bind the target
# to its own display rather than discarding display ownership via getApplicationContext().
method('smali/c/B9.smali','public static X(Landroid/content/Context;Ljava/lang/String;)V', '''
    .locals 0
    invoke-static {p0, p1}, Lru/drivedeck/headswap/DisplayOnlyLaunch;->launch(Landroid/content/Context;Ljava/lang/String;)Z
    return-void''')

def dock_fullscreen_controls(s):
    anchor = '    invoke-virtual {v1, v12, v0, v11, v9}, Lc/H3;->v0(Landroid/widget/LinearLayout;Landroid/view/View;II)V'
    pattern = r'(?ms)^\.method public final Y\(\)V\n.*?^\.end method'
    m = re.search(pattern, s)
    assert m and m.group().count(anchor) == 2 and 'Landroid/widget/Switch;->setChecked(Z)V' in m.group().split(anchor)[0]
    call = '    invoke-static {v12, v0}, Lru/drivedeck/headswap/DisplayOnlyLaunch;->addFullscreenControls(Landroid/widget/LinearLayout;Landroid/widget/TextView;)V'
    body = m.group().replace(anchor, anchor+'\n'+call, 1)
    return s[:m.start()]+body+s[m.end():]
edit('smali/c/H3.smali', dock_fullscreen_controls)
method(renderer,'public final v()V', '    .locals 1\n    const/4 v0, 0x0\n    invoke-static {p0, v0}, Lru/drivedeck/headswap/EmbeddedDisplayClient;->close(Landroid/view/View;Z)V\n    return-void')
method(renderer,'public final onSurfaceTextureDestroyed(Landroid/graphics/SurfaceTexture;)Z', '    .locals 1\n    invoke-static {p0, p1}, Lru/drivedeck/headswap/EmbeddedDisplayClient;->destroyed(Landroid/view/View;Landroid/graphics/SurfaceTexture;)Z\n    move-result v0\n    return v0')
# Close child producers before an invisible page is replaced by another pair.
def hide_panes(s):
    pat=r'(\.method public final d0\(Z\)V\n    \.locals \d+\n)'
    s,n=re.subn(pat,lambda m:m.group(1)+'    invoke-static {p0, p1}, Lru/drivedeck/headswap/EmbeddedDisplayClient;->hidePane(Ljava/lang/Object;Z)V\n',s);assert n==1
    pat=r'(\.method public final j0\(Lc/g8;ZJLc/F2;\)V\n    \.locals \d+\n)'
    add='    if-nez p2, :headswap_presentation_visible\n    if-eqz p1, :headswap_presentation_visible\n    invoke-static/range {p1 .. p2}, Lru/drivedeck/headswap/EmbeddedDisplayClient;->close(Landroid/view/View;Z)V\n    :headswap_presentation_visible\n'
    s,n=re.subn(pat,lambda m:m.group(1)+add,s);assert n==1
    return s
edit('smali/c/J2.smali',hide_panes)
method('smali/c/J2.smali','public final Y(Landroid/view/KeyEvent;)Z', '\n'.join([
    '    .locals 1',
    '    invoke-virtual {p0}, Lc/J2;->Z()Z',
    '    move-result v0',
    '    if-eqz v0, :headswap_key_rejected',
    '    invoke-virtual {p0}, Lc/J2;->y()I',
    '    move-result v0',
    '    invoke-static {v0, p1}, Lru/drivedeck/headswap/EmbeddedDisplayClient;->input(ILandroid/view/InputEvent;)Z',
    '    move-result v0', '    return v0',
    '    :headswap_key_rejected', '    const/4 v0, 0x0', '    return v0']))

# Native in-Activity split page replaces the original phone accessibility/overlay route.
method(main,'public final L()V', '    .locals 0\n    invoke-static {p0}, Lru/drivedeck/headswap/EmbeddedDisplayClient;->openSplit(Landroid/app/Activity;)V\n    return-void')
def internal_split_check(s):
    pat=r'(?ms)(^\.method public final K\(Z\)V\n.*?^\.end method)'
    def replace(m):
        body=m.group(0);begin=body.index('    invoke-static {p0}, Landroid/provider/Settings;->canDrawOverlays')
        # Keep the existing disabled/missing/busy guards; replace only the permission route.
        return body[:begin]+'    invoke-virtual {p0}, Lru/drivedeck/launcher/MainActivity;->L()V\n    :goto_1\n    :cond_5\n    return-void\n.end method'
    out,n=re.subn(pat,replace,s);assert n==1;return out
edit(main,internal_split_check)
# Startup capability is this secondary-display backend, never a fake system UID/root grant.
def startup_capability(s):
    token='    new-instance v1, Lc/w5;'
    assert s.count(token)==1
    return s.replace(token,'    invoke-static {v0}, Lru/drivedeck/headswap/EmbeddedDisplayClient;->capability(Landroid/content/Context;)Z\n    move-result p1\n'+token,1)
edit('smali/c/l5.1.smali',startup_capability)

# Preserve the exact old default only for strict private-preference migration.
def save_original_default(s):
    old=re.search(r'(?ms)^\.method public static c\(\)Lc/I0;\n.*?^\.end method',s)
    assert old
    return s+'\n'+old.group(0).replace('public static c()', 'public static headSwapOriginalDefault()',1)+'\n'
edit('smali/c/I0.smali',save_original_default)
# 30x36 grid: ordinary dashboard widgets occupy the entire usable width.
# Hidden WINDOW entries must remain present: u0.r() otherwise re-adds a default slot.
widgets=[
    ('b',0,0,False,7,10),       # clock
    ('c',7,0,False,11,10),      # weather
    ('g',18,0,False,12,10),     # speed
    ('d',0,10,False,15,26),     # music
    ('e',15,10,False,15,26),    # notifications
    ('f',0,0,True,7,6),        # navigation hint
    ('h',0,0,True,7,6),        # apps (sidebar picker is retained)
    ('j',0,0,True,15,18),       # Android widgets
    ('i',0,0,True,15,36),       # first embed slot
    ('m',0,0,True,15,36),       # second embed slot
    ('k',0,0,True,22,6),        # quick settings
    ('l',0,0,True,7,6),        # OBD
]
layout=['    .locals 10','    new-instance v0, Ljava/util/ArrayList;',
        '    invoke-direct {v0}, Ljava/util/ArrayList;-><init>()V']
for kind,x,y,hidden,columns,rows in widgets:
    layout += ['    new-instance v1, Lc/F0;',f'    sget-object v2, Lc/G0;->{kind}:Lc/G0;']
    for register,value in [(3,x),(4,y),(5,int(hidden)),(6,columns),(7,rows),(8,0)]:
        layout.append(f'    const/16 v{register}, 0x{value:x}')
    layout += ['    invoke-direct/range {v1 .. v8}, Lc/F0;-><init>(Lc/G0;IIZIIZ)V',
               '    invoke-virtual {v0, v1}, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z']
layout += ['    new-instance v1, Lc/I0;',
           '    invoke-direct {v1, v0}, Lc/I0;-><init>(Ljava/util/List;)V',
           '    invoke-static {v1}, Lc/I0;->t(Lc/I0;)Lc/I0;',
           '    move-result-object v0','    return-object v0']
edit('smali/c/I0.smali',lambda s:s+'\n.method public static headSwapPreviousDefault()Lc/I0;\n'+'\n'.join(layout)+'\n.end method\n')
# Map beside local music widget; only the user's selected app enters the map slot.
widgets=[('b',0,0,False,7,10),('c',7,0,False,8,10),('g',0,10,False,15,6),
         ('d',0,16,False,15,20),('e',0,0,True,7,18),('f',0,0,True,7,6),
         ('h',0,0,True,7,6),('j',0,0,True,15,18),('i',15,0,False,15,36),
         ('m',0,0,True,15,36),('k',0,0,True,22,6),('l',0,0,True,7,6)]
layout=['    .locals 10','    new-instance v0, Ljava/util/ArrayList;', '    invoke-direct {v0}, Ljava/util/ArrayList;-><init>()V']
for kind,x,y,hidden,columns,rows in widgets:
    layout += ['    new-instance v1, Lc/F0;',f'    sget-object v2, Lc/G0;->{kind}:Lc/G0;']
    for register,value in [(3,x),(4,y),(5,int(hidden)),(6,columns),(7,rows),(8,0)]: layout.append(f'    const/16 v{register}, 0x{value:x}')
    layout += ['    invoke-direct/range {v1 .. v8}, Lc/F0;-><init>(Lc/G0;IIZIIZ)V','    invoke-virtual {v0, v1}, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z']
layout += ['    new-instance v1, Lc/I0;','    invoke-direct {v1, v0}, Lc/I0;-><init>(Ljava/util/List;)V','    invoke-static {v1}, Lc/I0;->t(Lc/I0;)Lc/I0;','    move-result-object v0','    return-object v0']
method('smali/c/I0.smali','public static c()Lc/I0;', '\n'.join(layout))


# Never install a dimmer on Application's default-display WindowManager.
method('smali/c/g2.1.smali','public static b(Landroid/content/Context;)V',void)
method('smali/c/g2.1.smali','public a()V',void)
# Background player virtual displays are not part of this companion.
method('smali/c/B9.smali','public static Y(Landroid/content/Context;Ljava/lang/String;)V',void)
# Quick settings for radios, airplane mode and physical display cannot affect phone.
# U values 0..4,6 are device controls; value 5 is explicit user media mute.
def guard_device_controls(s):
    pat=r'(\.method public final Y\(II\)V\n    \.locals \d+\n)'
    addition='''
    const/4 v0, 0x5
    if-eq p1, v0, :headswap_user_media
    return-void
    :headswap_user_media
'''
    out,n=re.subn(pat,lambda m:m.group(1)+addition,s);assert n==1;return out
edit('smali/c/p4.1.smali',guard_device_controls)
# Keep settings locally useful but replace the old "choose default launcher" permission page.
method('smali/c/H3.smali','public final c0()V','''
    .locals 3
    invoke-virtual {p0}, Lc/H3;->l()Landroid/widget/LinearLayout;
    move-result-object v0
    const-string v1, "Режим HeadSwap: этот рабочий стол работает только на дополнительном дисплее. Домашний экран, тема и яркость телефона не меняются. Приложения открываются в выбранной области дополнительного дисплея. Встроенные окна приложений работают через активный доступ HeadSwap. Выбор домашнего экрана отключён."
    invoke-virtual {p0, v1}, Lc/H3;->j(Ljava/lang/String;)Landroid/widget/TextView;
    move-result-object v1
    invoke-virtual {v0, v1}, Landroid/view/ViewGroup;->addView(Landroid/view/View;)V
    iget-object v1, p0, Lc/H3;->j:Landroid/widget/LinearLayout;
    invoke-virtual {v1, v0}, Landroid/view/ViewGroup;->addView(Landroid/view/View;)V
    return-void
''')
# Disable install registration scheduler; retain local diagnostics initialization.
def app_scheduler(s):
    start=s.index('    invoke-static {}, Lc/a9;->u()Z',s.index('.method public final onCreate()V'))
    end=s.index('    invoke-static {p0}, Lc/I6;->W',start)
    return s[:start]+s[end:]
edit('smali/ru/drivedeck/launcher/DriveDeckApplication.smali', app_scheduler)
# The 0.6.8 head-unit hotspot feature must not start, stop or schedule tethering on the phone.
for signature in ('public final d()V', 'public final c(ZLjava/lang/Runnable;ZII)V'):
    method('smali/c/K4.smali', signature, void)
method('smali/c/c2.1.smali','public final E(Z)V',void)
method('smali/c/c2.1.smali','public final y()V',void)
# Notification cards use only their SBN key/package. The host resolves a current Activity
# PendingIntent and owns the fullscreen child, so no opaque service/broadcast or phone fallback runs.
method('smali/c/g6.1.smali', 'public final c(Landroid/content/Context;)V', """
    .locals 2
    iget-object v0, p0, Lc/g6;->f:Ljava/lang/String;
    iget-object v1, p0, Lc/g6;->a:Ljava/lang/String;
    invoke-static {p1, v0, v1}, Lru/drivedeck/headswap/EmbeddedDisplayClient;->openNotification(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;)V
    return-void
""")

# The original sidebar is one Canvas View. Capture its real status-icon draw bounds, including
# Canvas transforms. Handle only touches inside those rendered native controls, never an overlay.
def notification_status_controls(s):
    signature = '.method public final onDraw(Landroid/graphics/Canvas;)V\n    .locals 47\n'
    assert s.count(signature) == 1
    s = s.replace(signature, signature + '\n    invoke-static/range {p0 .. p1}, Lru/drivedeck/headswap/NotificationStatusBridge;->beginDraw(Landroid/view/View;Landroid/graphics/Canvas;)V\n')
    start = s.index(signature); end = s.index('.end method', start)
    body = s[start:end]
    assert body.count('    return-void') == 2
    body = body.replace('    return-void', '    invoke-static/range {p0 .. p1}, Lru/drivedeck/headswap/NotificationStatusBridge;->endDraw(Landroid/view/View;Landroid/graphics/Canvas;)V\n\n    return-void')
    s = s[:start]+body+s[end:]
    signature = '.method public final s(Landroid/graphics/Canvas;IFFFI)V'
    start = s.index(signature); end = s.index('.end method', start)
    body = s[start:end]
    anchor = '    invoke-virtual {v0, v1, v2, p3, p4}, Landroid/graphics/RectF;->set(FFFF)V'
    assert body.count(anchor)==1
    body = body.replace(anchor, anchor+'\n\n    invoke-static {p0, p1, p2, v0}, Lru/drivedeck/headswap/NotificationStatusBridge;->icon(Landroid/view/View;Landroid/graphics/Canvas;ILandroid/graphics/RectF;)V')
    s = s[:start]+body+s[end:]
    signature = '.method public final onTouchEvent(Landroid/view/MotionEvent;)Z\n    .locals 16\n'
    assert s.count(signature) == 1
    s=s.replace(signature, signature+"""
    move-object/from16 v0, p0
    iget-boolean v1, v0, Lc/q2;->z:Z
    if-nez v1, :headswap_original_status_touch
    invoke-static/range {p0 .. p1}, Lru/drivedeck/headswap/NotificationStatusBridge;->touch(Landroid/view/View;Landroid/view/MotionEvent;)Z
    move-result v0
    if-eqz v0, :headswap_original_status_touch
    return v0
    :headswap_original_status_touch
""")
    signature = '.method public final onInitializeAccessibilityNodeInfo(Landroid/view/accessibility/AccessibilityNodeInfo;)V\n    .locals 3\n'
    assert s.count(signature) == 1
    s = s.replace(signature, signature + """
    iget-boolean v0, p0, Lc/q2;->z:Z
    if-nez v0, :headswap_original_accessibility
    invoke-static {p0, p1}, Lru/drivedeck/headswap/NotificationStatusBridge;->accessibility(Landroid/view/View;Landroid/view/accessibility/AccessibilityNodeInfo;)V
    :headswap_original_accessibility
""")
    signature = '.method public final performAccessibilityAction(ILandroid/os/Bundle;)Z\n    .locals 3\n'
    assert s.count(signature) == 1
    s = s.replace(signature, signature + """
    iget-boolean v0, p0, Lc/q2;->z:Z
    if-nez v0, :headswap_original_accessibility_action
    invoke-static {p0, p1}, Lru/drivedeck/headswap/NotificationStatusBridge;->accessibilityAction(Landroid/view/View;I)Z
    move-result v0
    if-eqz v0, :headswap_original_accessibility_action
    return v0
    :headswap_original_accessibility_action
""")
    return s
edit('smali/c/q2.1.smali', notification_status_controls)
# New app must never initialize its dashboard on display 0.
def lifecycle(s):
    s=s.replace('# instance fields\n','# instance fields\n.field private headSwapInitialized:Z\n',1)
    assert 'headSwapInitialized:Z' in s
    token='    invoke-super/range {p0 .. p1}, Landroid/app/Activity;->onCreate(Landroid/os/Bundle;)V'
    assert s.count(token)==1
    s=s.replace(token,token+'''

    invoke-static {p0}, Lru/drivedeck/headswap/DisplayOnlyLaunch;->allowed(Landroid/content/Context;)Z
    move-result v1
    if-nez v1, :headswap_display_ready
    invoke-virtual {p0}, Landroid/app/Activity;->finish()V
    return-void
    :headswap_display_ready
    invoke-static {p0}, Lru/drivedeck/headswap/EmbeddedDisplayClient;->prepare(Landroid/content/Context;)V
    invoke-static {}, Lc/I0;->headSwapPreviousDefault()Lc/I0;
    move-result-object v1
    invoke-static {v1}, Lc/B9;->C(Lc/I0;)Ljava/lang/String;
    move-result-object v1
    invoke-static {p0, v1}, Lru/drivedeck/headswap/DisplayOnlyLaunch;->migrateDefaultLayout(Landroid/content/Context;Ljava/lang/String;)V
    invoke-static {}, Lc/I0;->headSwapOriginalDefault()Lc/I0;
    move-result-object v1
    invoke-static {v1}, Lc/B9;->C(Lc/I0;)Ljava/lang/String;
    move-result-object v1
    invoke-static {p0, v1}, Lru/drivedeck/headswap/DisplayOnlyLaunch;->migrateDefaultLayout(Landroid/content/Context;Ljava/lang/String;)V
    const/4 v1, 0x1
    iput-boolean v1, p0, Lru/drivedeck/launcher/MainActivity;->headSwapInitialized:Z
''',1)
    for name in ['onDestroy','onPause','onResume','onStop']:
        pat=r'(\.method public final '+name+r'\(\)V\n    \.locals \d+\n)'
        block='''
    iget-boolean v0, p0, Lru/drivedeck/launcher/MainActivity;->headSwapInitialized:Z
    if-nez v0, :headswap_initialized
    invoke-super {p0}, Landroid/app/Activity;->NAME()V
    return-void
    :headswap_initialized
'''.replace('NAME',name)
        if name in ('onPause', 'onStop', 'onDestroy'):
            block += '    invoke-static {p0}, Lru/drivedeck/headswap/EmbeddedDisplayClient;->pause(Landroid/app/Activity;)V\n'
        out,n=re.subn(pat,lambda m:m.group(1)+block,s); assert n==1,name;s=out
    # singleTask reuse and display removal can bypass onCreate.
    resume_pattern=r'(?ms)(\.method public final onResume\(\)V\n.*?^\.end method)'
    def resume_guard(m):
        return m.group(0).replace('    :headswap_initialized\n','''    :headswap_initialized
    invoke-static {p0}, Lru/drivedeck/headswap/DisplayOnlyLaunch;->allowed(Landroid/content/Context;)Z
    move-result v0
    if-nez v0, :headswap_resume_display_ready
    invoke-super {p0}, Landroid/app/Activity;->onResume()V
    invoke-virtual {p0}, Landroid/app/Activity;->finish()V
    return-void
    :headswap_resume_display_ready
    invoke-static {p0}, Lru/drivedeck/headswap/EmbeddedDisplayClient;->resume(Landroid/app/Activity;)V
''',1)
    s,n=re.subn(resume_pattern,resume_guard,s);assert n==1
    new_intent_super='    invoke-super {p0, p1}, Landroid/app/Activity;->onNewIntent(Landroid/content/Intent;)V'
    assert s.count(new_intent_super)==1
    s=s.replace(new_intent_super,new_intent_super+'''
    invoke-static {p0}, Lru/drivedeck/headswap/DisplayOnlyLaunch;->allowed(Landroid/content/Context;)Z
    move-result v0
    if-nez v0, :headswap_intent_display_ready
    invoke-virtual {p0}, Landroid/app/Activity;->finish()V
    return-void
    :headswap_intent_display_ready
''',1)
    assert 'onMovedToDisplay(' not in s
    s+='''
.method public onMovedToDisplay(ILandroid/content/res/Configuration;)V
    .locals 0
    invoke-super {p0, p1, p2}, Landroid/app/Activity;->onMovedToDisplay(ILandroid/content/res/Configuration;)V
    if-gtz p1, :headswap_still_secondary
    invoke-virtual {p0}, Landroid/app/Activity;->finish()V
    :headswap_still_secondary
    return-void
.end method
'''
    return s
edit(main,lifecycle)
# Every original Context/Activity activity-launch call is routed through guarded helper.
launches=0
for p in (root/'smali').rglob('*.smali'):
    old=p.read_text();new=old
    substitutions=[
      (r'invoke-virtual (\{[^}\n]+\}), Landroid/(?:app/Activity|content/Context);->startActivity\(Landroid/content/Intent;\)V',r'invoke-static \1, Lru/drivedeck/headswap/DisplayOnlyLaunch;->start(Landroid/content/Context;Landroid/content/Intent;)V'),
      (r'invoke-virtual (\{[^}\n]+\}), Landroid/(?:app/Activity|content/Context);->startActivity\(Landroid/content/Intent;Landroid/os/Bundle;\)V',r'invoke-static \1, Lru/drivedeck/headswap/DisplayOnlyLaunch;->start(Landroid/content/Context;Landroid/content/Intent;Landroid/os/Bundle;)V'),
      (r'invoke-virtual (\{[^}\n]+\}), Landroid/app/Activity;->startActivityForResult\(Landroid/content/Intent;I\)V',r'invoke-static \1, Lru/drivedeck/headswap/DisplayOnlyLaunch;->startForResult(Landroid/app/Activity;Landroid/content/Intent;I)V')]
    for pat,repl in substitutions:
        new,n=re.subn(pat,repl,new);launches+=n
    # Hardcoded own package/actions/providers follow separate applicationId; class names stay.
    def own_string(m):
        value=m.group(1)
        classes=['MainActivity','MusicEntry','PhoneEntry','DriveDeckApplication','LauncherAccessibilityService','NotificationBridgeService','DiagnosticUploadJob','StockMusicService','VoiceBootReceiver','UpdateInstallReceiver','DiagnosticExportProvider','LayoutExportProvider']
        if value=='ru.drivedeck.launcher' or (value.startswith('ru.drivedeck.launcher.') and value.split('.')[-1] not in classes):
            return '"'+value.replace('ru.drivedeck.launcher','ru.drivedeck.headswap',1)+'"'
        return m.group(0)
    new=re.sub(r'"(ru\.drivedeck\.launcher[^"\n]*)"',own_string,new)
    if new!=old:p.write_text(new);changes.append(str(p.relative_to(root)))
# Eliminate misleading unconsumed display-0 ActivityOptions block in B4.
edit('smali/c/i5.1.smali',lambda s:s.replace('    invoke-virtual {v2, v1}, Landroid/app/ActivityOptions;->setLaunchDisplayId(I)Landroid/app/ActivityOptions;', '    invoke-static {v0}, Lru/drivedeck/headswap/DisplayOnlyLaunch;->displayId(Landroid/content/Context;)I\n    move-result v3\n    invoke-virtual {v2, v3}, Landroid/app/ActivityOptions;->setLaunchDisplayId(I)Landroid/app/ActivityOptions;'))
# A separate applicationId/signature prevents replacement by stock APKs. Also
# remove its checks/download prompts, without touching weather or module data.
update_files, update_policy = disable_stock_app_updates(root)
changes.extend(update_files)
# Manifest: no system UID, HOME, launcher icon/aliases, boot entry or global overlays.
ET.register_namespace('android','http://schemas.android.com/apk/res/android');a='{http://schemas.android.com/apk/res/android}'
p=root/'AndroidManifest.xml';tree=ET.parse(p);m=tree.getroot();m.set('package','ru.drivedeck.headswap');m.set(a+'versionCode','334')
# Apktool's versionInfo overrides manifest attributes during rebuild.
yml=root/'apktool.yml'
yml_text,n=re.subn(r'(versionCode:)\s*[^\n]+', r'\g<1> 334', yml.read_text());assert n==1
yml.write_text(yml_text);changes.append('apktool.yml')
for key in ['sharedUserId','sharedUserLabel','sharedUserMaxSdkVersion']:m.attrib.pop(a+key,None)
remove_permissions={'RECEIVE_BOOT_COMPLETED','SYSTEM_ALERT_WINDOW','MANAGE_ACTIVITY_TASKS','REAL_GET_TASKS','START_TASKS_FROM_RECENTS','INJECT_EVENTS','WRITE_SECURE_SETTINGS','MODIFY_DAY_NIGHT_MODE','ADD_TRUSTED_DISPLAY','START_ANY_ACTIVITY','INTERNAL_SYSTEM_WINDOW','ACTIVITY_EMBEDDING','BIND_APPWIDGET','CHANGE_WIFI_STATE','MODIFY_PHONE_STATE','INSTALL_PACKAGES','BLUETOOTH_PRIVILEGED','TETHER_PRIVILEGED','WRITE_SETTINGS','MANAGE_APP_OPS_MODES'}
for el in list(m):
    if el.tag=='uses-permission' and el.get(a+'name','').removeprefix('android.permission.') in remove_permissions:m.remove(el)
    elif el.tag in ('permission','uses-permission'):
        name=el.get(a+'name','');el.set(a+'name',name.replace('ru.drivedeck.launcher.','ru.drivedeck.headswap.'))
ET.SubElement(m,'uses-permission',{a+'name':'com.headswap.desk.permission.EMBED_DISPLAY'})
queries=m.find('queries')
if queries is None: queries=ET.SubElement(m,'queries')
ET.SubElement(queries,'package',{a+'name':'com.headswap.desk'})
app=m.find('application');app.set(a+'label','DriveDeck · HeadSwap');app.set(a+'allowBackup','false')
for key in ['fullBackupContent','dataExtractionRules']:app.attrib.pop(a+key,None)
for el in list(app):
    name=el.get(a+'name','')
    if el.tag in ('activity-alias','receiver') or name.endswith(('.DiagnosticUploadJob','.LauncherAccessibilityService','.TechBtBridgeService')):
        app.remove(el);continue
    if el.tag=='activity':
        for filt in el.findall('intent-filter'):el.remove(filt)
        el.set(a+'resizeableActivity','true');el.set(a+'taskAffinity','ru.drivedeck.headswap.desktop');el.set(a+'launchMode','singleTask')
    if el.tag=='provider':el.set(a+'authorities',el.get(a+'authorities').replace('ru.drivedeck.launcher.','ru.drivedeck.headswap.'))
ET.indent(tree,space='    ');tree.write(p,encoding='utf-8',xml_declaration=True)
changes.append('AndroidManifest.xml')
# No original direct launch remains (the helper is supplied as separate DEX).
left=[]
for p in (root/'smali').rglob('*.smali'):
    if re.search(r'->startActivity(?:ForResult)?\(',p.read_text()):left.append(str(p))
assert not left,left
(root.parent/'patch-report.json').write_text(json.dumps({'original_sha256':'e32ac90ed66d7d325653ed475cd3623d41d7737aeb321813991aedab7b06a003','application_id':'ru.drivedeck.headswap','version_code':334,'app_update_policy':update_policy,'activity':'ru.drivedeck.launcher.MainActivity','rewritten_activity_launches':launches,'files_modified':sorted(set(changes)),'disabled':['HOME/LAUNCHER/ASSIST registrations and aliases','boot receivers and system UID','HOME/role selection and global settings intents','system night mode and global dimmer','root probes and shell command execution','opaque notification sends; keys now resolve through the host managed fullscreen gateway','unprivileged TextureView virtual-display creation and background player display','runtime automatic navigator fallback; embedded launches cannot become fullscreen','phone accessibility/overlay split route','install telemetry and diagnostic upload','device/radio/display quick controls; explicit media mute retained','APK installer intents'],'retained':['original APK untouched','class names and original app resources','local diagnostic logging','ordinary own-display fullscreen app launch','display guards on create/resume/new-intent/moved-to-display','only exact legacy default layout preferences migrate; customized layouts and other preferences retained','explicit user media controls','shell-owned embedded map and internal two-app page via signature gateway'],'license_check':'No licensing/DRM implementation identified in static analysis; no signature/entitlement bypass applied.','validation':'Build and runtime results recorded separately'},ensure_ascii=False,indent=2))
print('Patched',len(set(changes)),'files;',launches,'activity launches guarded')
