"""Disable only the stock application updater in the authorized 0.6.8 APK."""
import hashlib
import re
from pathlib import Path

NOTICE = 'Для обновления откройте HeadSwap Desktop и выберите «Компоненты». Обновление также можно установить вручную из выпуска HeadSwap.'
CHECK = 'public static a(Landroid/content/Context;Lc/T4;ZLandroid/view/View;Lc/M9;)V'
DOWNLOAD = 'public static c(Landroid/content/Context;Lc/G9;Landroid/view/View;Lc/N9;)V'
APP_FETCH = 'public static d(Landroid/content/Context;Ljava/lang/String;Ljava/io/File;Lc/G9;Lc/C7;J)V'
TIMER = 'public final run()V'
PAGE = 'public final d0()V'
PAGE_CUT = ('    invoke-virtual {v0, v1, v2, v6, v8}, Lc/x3;->u0(Landroid/widget/LinearLayout;Landroid/view/View;II)V\n\n'
            '    new-instance v2, Landroid/widget/LinearLayout;')


def method_text(text, signature):
    matches = re.findall(r'(?ms)^\.method ' + re.escape(signature) + r'\n.*?^\.end method', text)
    if len(matches) != 1:
        raise ValueError('Expected exactly one method: ' + signature)
    return matches[0]


def replace_method(text, signature, body):
    old = method_text(text, signature)
    return text.replace(old, '.method ' + signature + '\n' + body.strip() + '\n.end method', 1)


def methods(text):
    return dict(re.findall(r'(?ms)^\.method ([^\n]+)\n(.*?)^\.end method', text))


def patch_api(text):
    # The check callback is only completed for a manual request. Automatic callers
    # must stay silent; neither path schedules network work or mutates preferences.
    out = replace_method(text, CHECK, f'''
    .locals 1
    if-eqz p2, :headswap_no_check
    if-eqz p4, :headswap_no_check
    const-string v0, "{NOTICE}"
    invoke-interface {{p4, v0}}, Lc/M9;->a(Ljava/lang/String;)V
    :headswap_no_check
    return-void''')
    out = replace_method(out, DOWNLOAD, f'''
    .locals 1
    if-eqz p3, :headswap_no_download
    const-string v0, "{NOTICE}"
    invoke-interface {{p3, v0}}, Lc/N9;->a(Ljava/lang/String;)V
    :headswap_no_download
    return-void''')
    out = replace_method(out, APP_FETCH, f'''
    .locals 2
    new-instance v0, Ljava/lang/IllegalStateException;
    const-string v1, "{NOTICE}"
    invoke-direct {{v0, v1}}, Ljava/lang/IllegalStateException;-><init>(Ljava/lang/String;)V
    throw v0''')
    before, after = methods(text), methods(out)
    assert before.keys() == after.keys()
    assert all(before[sig] == after[sig] for sig in before if sig not in {CHECK, DOWNLOAD, APP_FETCH})
    return out


def patch_timer(text):
    old = method_text(text, TIMER)
    start = old.index('    :pswitch_0\n')
    end = old.index('    :pswitch_data_0\n', start)
    assert 'Lc/O9;->a(' in old[start:end]
    # The default branch is the weather timer; retain it exactly, including rescheduling.
    assert 'Lc/ka;->h(' in old[:start]
    assert 'postDelayed' in old[:start]
    replacement = old[:start] + '    :pswitch_0\n    return-void\n\n' + old[end:]
    return text.replace(old, replacement, 1)


def patch_page(text):
    old = method_text(text, PAGE)
    assert old.count(PAGE_CUT) == 1, 'Unrecognized settings page boundary'
    split = old.index(PAGE_CUT) + PAGE_CUT.index('    new-instance')
    prefix = old[:split]
    # Registers are fixed by the exact supported input APK: v0=this, v3=root,
    # v4=presets, v5=device information, v1=diagnostics. Keep those original sections.
    assert '.locals 38' in prefix and 'Lc/J2;' in prefix and 'getRealMetrics' in prefix
    replacement = prefix + f'''
    const/16 v6, 0x8
    const/4 v7, 0x0
    invoke-virtual {{v0, v3, v4, v6, v7}}, Lc/x3;->u0(Landroid/widget/LinearLayout;Landroid/view/View;II)V
    invoke-virtual {{v0, v3, v5, v6, v7}}, Lc/x3;->u0(Landroid/widget/LinearLayout;Landroid/view/View;II)V
    invoke-virtual {{v0, v3, v1, v6, v7}}, Lc/x3;->u0(Landroid/widget/LinearLayout;Landroid/view/View;II)V
    const-string v1, "DriveDeck · HeadSwap 0.6.8 (301)"
    invoke-virtual {{v0, v1}}, Lc/x3;->o0(Ljava/lang/String;)Landroid/widget/LinearLayout;
    move-result-object v1
    const-string v2, "{NOTICE}"
    invoke-virtual {{v0, v2}}, Lc/x3;->i(Ljava/lang/String;)Landroid/widget/TextView;
    move-result-object v2
    invoke-virtual {{v0, v1, v2, v6, v6}}, Lc/x3;->u0(Landroid/widget/LinearLayout;Landroid/view/View;II)V
    invoke-virtual {{v0, v3, v1, v6, v7}}, Lc/x3;->u0(Landroid/widget/LinearLayout;Landroid/view/View;II)V
    return-void
.end method'''
    assert '"update_auto"' not in replacement and 'Lc/y9;' not in replacement
    return text.replace(old, replacement, 1)


def apply(root: Path):
    transforms = {'smali/c/O9.smali': patch_api, 'smali/c/t5.1.smali': patch_timer, 'smali/c/x3.1.smali': patch_page}
    evidence = {}
    for relative, transform in transforms.items():
        path = root / relative
        original = path.read_text()
        changed = transform(original)
        assert changed != original
        if relative.endswith('O9.smali'):
            preserved = {sig: body for sig, body in methods(original).items() if sig not in {CHECK, DOWNLOAD, APP_FETCH}}
            evidence['preserved_shared_methods_sha256'] = {sig: hashlib.sha256(body.encode()).hexdigest() for sig, body in preserved.items()}
        path.write_text(changed)
    evidence.update({'stock_app_update_checks': 'DISABLED', 'stock_app_downloads': 'DISABLED',
                     'stock_update_timer': 'DISABLED', 'settings_page': 'HeadSwap-only update guidance; presets/device information/diagnostics retained',
                     'weather_timer': 'UNCHANGED', 'shared_network_module_helpers': 'UNCHANGED',
                     'settings_page_prefix_preserved': True})
    return list(transforms), evidence
