# Bloco C — Fase C1: inventário de preferências, storage e IPC
Base: `459acdb8670f8d7469e2138f87452429afb78f7c` (merge dos Blocos A+B em `integration/community`).
Este documento é o inventário exigido pela Fase C1 do plano de execução. Ele é a
fonte da qual `PreferenceSchema` e a allowlist de backup são derivados na Fase C2.

## 1. Método
A superfície autoritativa de *configuração do usuário* é o que a UI declara:

- atributos `key` das telas em `app/src/main/res/xml/` (standalone e `embedded_settings_*`);
- entradas de `app/src/main/res/raw/waex_settings_map.json`;
- chaves da barra flutuante, declaradas em código em `BottomBarPreferenceSchema`
  porque `BottomBarCustomizationActivity` é uma Activity com sliders, não um `PreferenceScreen`.

O tipo armazenado é derivado do widget que declara a chave. Leitores e escritores são
localizados por varredura dos fontes Java, excluindo os arquivos de catálogo
(`FeatureCatalog`, `BackupCodec`, `BottomBarPreferenceSchema`, `SettingsIconRegistry`),
que apenas citam chaves sem consumi-las.

Total de chaves que armazenam valor: **181**. Além delas, 27 entradas de UI são
categorias ou itens de navegação e não persistem nada.

## 2. Classificação
| Classe | Chaves | Regra |
|---|---:|---|
| `public` | 147 | lida por um hook dentro do processo do WhatsApp e não carrega segredo |
| `private` | 15 | lida apenas no processo do app do módulo, ou caminho absoluto local do aparelho |
| `secret` | 3 | conteúdo é segredo do usuário; nunca pode ficar em arquivo world-readable |
| `cache` | 1 | estado regenerável; nunca exportado |
| `obsolete` | 15 | declarada na UI sem nenhuma implementação; removida (ver secção 5) |

## 3. Segredos no armazenamento público — achado principal do C1
Três chaves guardam segredo do usuário no arquivo de preferências padrão, que
`WppXposed` torna **world-readable** (`getDefaultSharedPreferencesMode` é hookado para
`MODE_WORLD_READABLE`) para que `XSharedPreferences` as leia do processo do WhatsApp.
Além do arquivo em si, `HookProvider` é `exported="true"` sem permissão e atende
`get_all_preferences` a qualquer aplicativo instalado.

| Chave | Conteúdo | Lida em | Consumidor |
|---|---|---|---|
| `assemblyai_key` | chave de API AssemblyAI fornecida pelo usuário | processo do WhatsApp | AudioTranscript.java |
| `bootloader_spoofer_xml` | keybox XML importado, contendo chave privada e cadeia de certificados | processo do WhatsApp | HookBL.java |
| `groq_api_key` | chave de API Groq fornecida pelo usuário | processo do WhatsApp | AudioTranscript.java |

Contrato alvo: essas três saem do `public_config` e passam a ser lidas sob demanda
por chamada validada por UID, sem nunca serem gravadas no arquivo world-readable.

## 4. Matriz `key -> writer -> reader -> process -> sensitivity -> migration`
`process`: `wa` = processo do WhatsApp hookado; `app` = processo do app do módulo;
`app+wa` = ambos.

| Chave | Tipo | Writer | Reader | Processo | Sensibilidade | Migração |
|---|---|---|---|---|---|---|
| `stamp_copied_message` | boolean | — | Others.java | wa | cache | private_config; not exported |
| `add_status_reply_menu_item` | boolean | — | — | none | obsolete | remove from UI; never re-read |
| `always_typing_global_type` | string | — | — | none | obsolete | remove from UI; never re-read |
| `call_recording_calls_tab_menu` | boolean | — | — | none | obsolete | remove from UI; never re-read |
| `delete_message_file` | boolean | — | — | none | obsolete | remove from UI; never re-read |
| `delete_message_file_sent` | boolean | — | — | none | obsolete | remove from UI; never re-read |
| `disable_status_swipe_up` | boolean | — | — | none | obsolete | remove from UI; never re-read |
| `enable_spy` | boolean | — | — | none | obsolete | remove from UI; never re-read |
| `file_size_spoofer` | boolean | — | — | none | obsolete | remove from UI; never re-read |
| `message_bomber` | boolean | — | — | none | obsolete | remove from UI; never re-read |
| `pro_status_splitter` | boolean | — | — | none | obsolete | remove from UI; never re-read |
| `send_audio_as_voice_status` | boolean | — | — | none | obsolete | remove from UI; never re-read |
| `status_bottom_play_pause_button` | boolean | — | — | none | obsolete | remove from UI; never re-read |
| `status_video_fast_gesture` | boolean | — | — | none | obsolete | remove from UI; never re-read |
| `status_video_fast_speed` | string | — | — | none | obsolete | remove from UI; never re-read |
| `statuscomposer` | boolean | — | — | none | obsolete | remove from UI; never re-read |
| `always_typing_contacts` | string | — | PrivacyFragment.java | app | private | private_config; exported when user-owned |
| `always_typing_global` | boolean | — | MainActivity.java<br>SmartTypingTileService.java<br>+1 | app | private | private_config; exported when user-owned |
| `always_typing_global_mode` | string | — | PrivacyFragment.java | app | private | private_config; exported when user-owned |
| `always_typing_global_target` | string | — | PrivacyFragment.java | app | private | private_config; exported when user-owned |
| `call_recording_path` | string | — | RecordingsFragment.java<br>CallRecording.java | app+wa | private | private_config; exported when user-owned |
| `floating_bottom_bar_icon_label_spacing` | float | — | BottomBarCustomizationActivity.java | app | private | private_config; exported when user-owned |
| `floating_bottom_bar_icon_size` | float | — | BottomBarCustomizationActivity.java | app | private | private_config; exported when user-owned |
| `floating_bottom_bar_minimal_fab_margin` | float | — | BottomBarCustomizationActivity.java | app | private | private_config; exported when user-owned |
| `floating_bottom_bar_text_size` | float | — | BottomBarCustomizationActivity.java | app | private | private_config; exported when user-owned |
| `folder_theme` | string | — | TextEditorActivity.java<br>WallpaperView.java<br>+1 | app+wa | private | private_config; exported when user-owned |
| `thememode` | string | — | App.java<br>BasePreferenceFragment.java | app | private | private_config; exported when user-owned |
| `verify_blocked_contact` | boolean | App.java | — | app | private | private_config; exported when user-owned |
| `video_call_screen_rec` | boolean | — | MediaFragment.java | app | private | private_config; exported when user-owned |
| `wae_color_mode` | string | — | BaseActivity.java | app | private | private_config; exported when user-owned |
| `wallpaper_file` | string | — | WallpaperView.java | app | private | private_config; exported when user-owned |
| `admin_emoji` | string | — | GroupAdmin.java | wa | public | public_config; shadow-write + dual-read |
| `admin_grp` | boolean | — | GroupAdmin.java | wa | public | public_config; shadow-write + dual-read |
| `alertsticker` | boolean | — | Stickers.java | wa | public | public_config; shadow-write + dual-read |
| `always_online` | boolean | — | MainActivity.java<br>AlwaysOnlineTileService.java<br>+1 | app+wa | public | public_config; shadow-write + dual-read |
| `ampm` | boolean | — | CustomTime.java | wa | public | public_config; shadow-write + dual-read |
| `animation_emojis` | boolean | — | Others.java | wa | public | public_config; shadow-write + dual-read |
| `animation_list` | string | — | Others.java | wa | public | public_config; shadow-write + dual-read |
| `antidisappearing` | boolean | — | ChatLimit.java | wa | public | public_config; shadow-write + dual-read |
| `antieditmessages` | boolean | — | ShowEditMessage.java | wa | public | public_config; shadow-write + dual-read |
| `antirevoke` | string | — | AntiRevoke.java | wa | public | public_config; shadow-write + dual-read |
| `antirevokestatus` | string | — | AntiRevoke.java | wa | public | public_config; shadow-write + dual-read |
| `audio_transcription` | boolean | — | Others.java<br>AudioTranscript.java | wa | public | public_config; shadow-write + dual-read |
| `audio_type` | string | — | Others.java | wa | public | public_config; shadow-write + dual-read |
| `auto_status_forward` | boolean | — | AutoStatusForward.java | wa | public | public_config; shadow-write + dual-read |
| `auto_status_forward_rules_pref` | string | — | EmbeddedBasePreferenceFragment.java | wa | public | public_config; shadow-write + dual-read |
| `autonext_status` | boolean | — | Others.java | wa | public | public_config; shadow-write + dual-read |
| `blueonreply` | boolean | — | BasePreferenceFragment.java<br>SeenTick.java<br>+1 | app+wa | public | public_config; shadow-write + dual-read |
| `bootloader_spoofer` | boolean | — | WppXposed.java<br>FeatureLoader.java<br>+1 | app+wa | public | public_config; shadow-write + dual-read |
| `bootloader_spoofer_custom` | boolean | — | HookBL.java | wa | public | public_config; shadow-write + dual-read |
| `broadcast_tag` | boolean | — | TagMessage.java | wa | public | public_config; shadow-write + dual-read |
| `bubble_color` | boolean | — | BubbleColors.java | wa | public | public_config; shadow-write + dual-read |
| `bypass_version_check` | boolean | — | FeatureLoader.java | wa | public | public_config; shadow-write + dual-read |
| `call_block_contacts` | string | — | BasePreferenceFragment.java<br>EmbeddedBasePreferenceFragment.java<br>+1 | app+wa | public | public_config; shadow-write + dual-read |
| `call_info` | boolean | — | Others.java | wa | public | public_config; shadow-write + dual-read |
| `call_privacy` | string | — | MainActivity.java<br>BlockCallsTileService.java<br>+4 | app+wa | public | public_config; shadow-write + dual-read |
| `call_recording_blacklist` | string | — | MediaFragment.java<br>CallRecording.java | app+wa | public | public_config; shadow-write + dual-read |
| `call_recording_enable` | boolean | — | MediaFragment.java<br>CallRecording.java<br>+1 | app+wa | public | public_config; shadow-write + dual-read |
| `call_recording_mode` | string | — | MediaFragment.java<br>CallRecording.java | app+wa | public | public_config; shadow-write + dual-read |
| `call_recording_toast` | boolean | — | CallRecording.java | wa | public | public_config; shadow-write + dual-read |
| `call_recording_whitelist` | string | — | MediaFragment.java<br>CallRecording.java | app+wa | public | public_config; shadow-write + dual-read |
| `call_type` | string | — | CallPrivacy.java | wa | public | public_config; shadow-write + dual-read |
| `call_white_contacts` | string | — | BasePreferenceFragment.java<br>EmbeddedBasePreferenceFragment.java<br>+1 | app+wa | public | public_config; shadow-write + dual-read |
| `calltype` | boolean | CallType.java | — | wa | public | public_config; shadow-write + dual-read |
| `change_dpi` | string | — | BottomSheetHelper.java<br>CustomView.java | app+wa | public | public_config; shadow-write + dual-read |
| `changecolor` | boolean | — | BasePreferenceFragment.java<br>CustomThemeV2.java<br>+3 | app+wa | public | public_config; shadow-write + dual-read |
| `changecolor_mode` | string | — | BasePreferenceFragment.java<br>CustomThemeV2.java<br>+2 | app+wa | public | public_config; shadow-write + dual-read |
| `channels` | boolean | — | NoticeCenter.java<br>BasePreferenceFragment.java<br>+2 | app+wa | public | public_config; shadow-write + dual-read |
| `chatfilter` | string | — | Others.java | wa | public | public_config; shadow-write + dual-read |
| `copystatus` | boolean | — | CopyStatus.java | wa | public | public_config; shadow-write + dual-read |
| `css_theme` | string | — | CustomView.java | wa | public | public_config; shadow-write + dual-read |
| `custom_filters` | boolean | — | BasePreferenceFragment.java<br>BubbleColors.java<br>+4 | app+wa | public | public_config; shadow-write + dual-read |
| `custom_privacy_type` | string | — | CustomPrivacy.java | wa | public | public_config; shadow-write + dual-read |
| `customize_supported_versions` | boolean | — | SupportedVersionsActivity.java<br>FeatureLoader.java | app+wa | public | public_config; shadow-write + dual-read |
| `disable_ads` | boolean | — | Others.java | wa | public | public_config; shadow-write + dual-read |
| `disable_defemojis` | boolean | — | Others.java | wa | public | public_config; shadow-write + dual-read |
| `disable_expiration` | boolean | — | Others.java | wa | public | public_config; shadow-write + dual-read |
| `disable_profile_status` | boolean | — | Others.java | wa | public | public_config; shadow-write + dual-read |
| `disable_sensor_proximity` | boolean | — | MainActivity.java<br>ProximitySensorSwitchTileService.java<br>+1 | app+wa | public | public_config; shadow-write + dual-read |
| `dotonline` | boolean | — | BasePreferenceFragment.java<br>ShowOnline.java<br>+1 | app+wa | public | public_config; shadow-write + dual-read |
| `doubletap2like` | boolean | — | Others.java | wa | public | public_config; shadow-write + dual-read |
| `download_local` | string | — | Utils.java | wa | public | public_config; shadow-write + dual-read |
| `download_video_note` | boolean | — | BasePreferenceFragment.java<br>DownloadVideoNote.java | app+wa | public | public_config; shadow-write + dual-read |
| `downloadstatus` | boolean | — | BasePreferenceFragment.java<br>StatusDownload.java | app+wa | public | public_config; shadow-write + dual-read |
| `downloadviewonce` | boolean | — | BasePreferenceFragment.java<br>DownloadViewOnce.java | app+wa | public | public_config; shadow-write + dual-read |
| `filter_group_members_messages` | boolean | — | GeneralFragment.java<br>FeatureLoader.java<br>+1 | app+wa | public | public_config; shadow-write + dual-read |
| `filter_items` | string | FilterItemsActivity.java | CustomizationFragment.java<br>Others.java | app+wa | public | public_config; shadow-write + dual-read |
| `filtergroups` | boolean | BasePreferenceFragment.java<br>EmbeddedBasePreferenceFragment.java | FilterGroups.java | app+wa | public | public_config; shadow-write + dual-read |
| `filterseen` | boolean | — | Others.java | wa | public | public_config; shadow-write + dual-read |
| `floating_bottom_bar` | boolean | BottomBarCustomizationActivity.java | LocalDiagnostics.java<br>FloatingBottomBar.java | app+wa | public | public_config; shadow-write + dual-read |
| `floating_bottom_bar_bottom_margin` | float | — | BottomBarCustomizationActivity.java<br>FloatingBottomBar.java | app+wa | public | public_config; shadow-write + dual-read |
| `floating_bottom_bar_fab_offset` | float | — | BottomBarCustomizationActivity.java<br>FloatingBottomBar.java | app+wa | public | public_config; shadow-write + dual-read |
| `floating_bottom_bar_glass_opacity` | float | — | BottomBarCustomizationActivity.java<br>FloatingBottomBar.java | app+wa | public | public_config; shadow-write + dual-read |
| `floating_bottom_bar_horizontal_margin` | float | — | BottomBarCustomizationActivity.java<br>FloatingBottomBar.java | app+wa | public | public_config; shadow-write + dual-read |
| `floating_bottom_bar_indicator_height` | float | — | BottomBarCustomizationActivity.java<br>FloatingBottomBar.java | app+wa | public | public_config; shadow-write + dual-read |
| `floating_bottom_bar_indicator_offset` | float | — | BottomBarCustomizationActivity.java<br>FloatingBottomBar.java | app+wa | public | public_config; shadow-write + dual-read |
| `floating_bottom_bar_indicator_opacity` | float | — | BottomBarCustomizationActivity.java<br>FloatingBottomBar.java | app+wa | public | public_config; shadow-write + dual-read |
| `floating_bottom_bar_indicator_padding_horizontal` | float | — | BottomBarCustomizationActivity.java<br>FloatingBottomBar.java | app+wa | public | public_config; shadow-write + dual-read |
| `floating_bottom_bar_indicator_padding_vertical` | float | — | BottomBarCustomizationActivity.java<br>FloatingBottomBar.java | app+wa | public | public_config; shadow-write + dual-read |
| `floating_bottom_bar_indicator_radius` | float | — | BottomBarCustomizationActivity.java<br>FloatingBottomBar.java | app+wa | public | public_config; shadow-write + dual-read |
| `floating_bottom_bar_indicator_width` | float | — | BottomBarCustomizationActivity.java<br>FloatingBottomBar.java | app+wa | public | public_config; shadow-write + dual-read |
| `floating_bottom_bar_manual_height` | float | — | BottomBarCustomizationActivity.java<br>FloatingBottomBar.java | app+wa | public | public_config; shadow-write + dual-read |
| `floating_bottom_bar_minimal_fab_opacity` | float | — | BottomBarCustomizationActivity.java<br>FloatingBottomBar.java | app+wa | public | public_config; shadow-write + dual-read |
| `floating_bottom_bar_minimal_fab_radius` | float | — | BottomBarCustomizationActivity.java<br>FloatingBottomBar.java | app+wa | public | public_config; shadow-write + dual-read |
| `floating_bottom_bar_minimal_fab_size` | float | — | BottomBarCustomizationActivity.java<br>FloatingBottomBar.java | app+wa | public | public_config; shadow-write + dual-read |
| `floating_bottom_bar_padding_vertical` | float | — | BottomBarCustomizationActivity.java<br>FloatingBottomBar.java | app+wa | public | public_config; shadow-write + dual-read |
| `floating_bottom_bar_radius` | float | — | BottomBarCustomizationActivity.java<br>FloatingBottomBar.java | app+wa | public | public_config; shadow-write + dual-read |
| `floatingmenu` | boolean | — | Others.java | wa | public | public_config; shadow-write + dual-read |
| `force_english` | boolean | — | App.java<br>BasePreferenceFragment.java<br>+1 | app+wa | public | public_config; shadow-write + dual-read |
| `force_restore_backup_feature` | boolean | — | BackupRestore.java | wa | public | public_config; shadow-write + dual-read |
| `freezelastseen` | boolean | — | MainActivity.java<br>BasePreferenceFragment.java<br>+2 | app+wa | public | public_config; shadow-write + dual-read |
| `ghostmode` | boolean | — | MainActivity.java<br>MenuHome.java | app+wa | public | public_config; shadow-write + dual-read |
| `ghostmode_r` | boolean | — | PrivacyFragment.java<br>CustomPrivacy.java<br>+1 | app+wa | public | public_config; shadow-write + dual-read |
| `ghostmode_t` | boolean | — | PrivacyFragment.java<br>CustomPrivacy.java<br>+1 | app+wa | public | public_config; shadow-write + dual-read |
| `go_to_first_message` | boolean | — | ChatScrollButtons.java | wa | public | public_config; shadow-write + dual-read |
| `google_translate` | boolean | — | GoogleTranslate.java | wa | public | public_config; shadow-write + dual-read |
| `hide_seen_view` | boolean | — | HideSeenView.java | wa | public | public_config; shadow-write + dual-read |
| `hideaudioseen` | boolean | — | HideSeen.java | wa | public | public_config; shadow-write + dual-read |
| `hideonceseen` | boolean | — | HideSeen.java | wa | public | public_config; shadow-write + dual-read |
| `hideread` | boolean | BasePreferenceFragment.java<br>EmbeddedBasePreferenceFragment.java | MainActivity.java<br>StealthReadTicksTileService.java<br>+2 | app+wa | public | public_config; shadow-write + dual-read |
| `hideread_group` | boolean | — | HideSeen.java | wa | public | public_config; shadow-write + dual-read |
| `hidereceipt` | boolean | — | MainActivity.java<br>HideDeliveredTileService.java<br>+3 | app+wa | public | public_config; shadow-write + dual-read |
| `hidestatusview` | boolean | — | MainActivity.java<br>StealthStatusViewingTileService.java<br>+3 | app+wa | public | public_config; shadow-write + dual-read |
| `hidetabs` | string_set | — | HideTabs.java | wa | public | public_config; shadow-write + dual-read |
| `hidetag` | boolean | — | TagMessage.java | wa | public | public_config; shadow-write + dual-read |
| `igstatus` | boolean | — | BasePreferenceFragment.java<br>HideTabs.java<br>+3 | app+wa | public | public_config; shadow-write + dual-read |
| `imagequality` | boolean | — | MediaQuality.java | wa | public | public_config; shadow-write + dual-read |
| `lite_mode` | boolean | — | FileSelectPreference.java<br>BasePreferenceFragment.java<br>+6 | app+wa | public | public_config; shadow-write + dual-read |
| `lockedchats_enhancer` | boolean | — | LockedChatsEnhancer.java | wa | public | public_config; shadow-write + dual-read |
| `media_preview` | boolean | — | MediaPreview.java | wa | public | public_config; shadow-write + dual-read |
| `menuwicon` | boolean | — | Others.java | wa | public | public_config; shadow-write + dual-read |
| `message_device_source` | boolean | — | MessageDeviceSourceStore.java<br>Others.java | wa | public | public_config; shadow-write + dual-read |
| `metaai` | boolean | — | HideTabsPreference.java<br>HideTabs.java<br>+1 | app+wa | public | public_config; shadow-write + dual-read |
| `newchat` | boolean | — | NewChat.java<br>MenuHome.java | wa | public | public_config; shadow-write + dual-read |
| `novaconfig` | boolean | — | Others.java | wa | public | public_config; shadow-write + dual-read |
| `oldstatus` | boolean | — | BasePreferenceFragment.java<br>Others.java<br>+1 | app+wa | public | public_config; shadow-write + dual-read |
| `open_settings_mode` | string | — | BasePreferenceFragment.java<br>Utils.java | app+wa | public | public_config; shadow-write + dual-read |
| `open_waex` | string | — | BasePreferenceFragment.java<br>ProviderSharedPreferences.java<br>+3 | app+wa | public | public_config; shadow-write + dual-read |
| `pinnedlimit` | boolean | — | PinnedLimit.java | wa | public | public_config; shadow-write + dual-read |
| `proximity_audios` | boolean | — | Others.java | wa | public | public_config; shadow-write + dual-read |
| `remove_status_bottom_tile` | boolean | — | BasePreferenceFragment.java<br>EmbeddedBasePreferenceFragment.java | app+wa | public | public_config; shadow-write + dual-read |
| `remove_status_heart_button` | boolean | — | BasePreferenceFragment.java<br>EmbeddedBasePreferenceFragment.java | app+wa | public | public_config; shadow-write + dual-read |
| `remove_status_quick_reactions` | boolean | — | BasePreferenceFragment.java<br>EmbeddedBasePreferenceFragment.java | app+wa | public | public_config; shadow-write + dual-read |
| `removechannel_rec` | boolean | — | BasePreferenceFragment.java<br>Channels.java<br>+1 | app+wa | public | public_config; shadow-write + dual-read |
| `removeforwardlimit` | boolean | — | ShareLimit.java | wa | public | public_config; shadow-write + dual-read |
| `removeseemore` | boolean | — | ChatLimit.java | wa | public | public_config; shadow-write + dual-read |
| `restartbutton` | boolean | — | MenuHome.java | wa | public | public_config; shadow-write + dual-read |
| `revokeallmessages` | boolean | — | ChatLimit.java | wa | public | public_config; shadow-write + dual-read |
| `secondstotime` | string | — | CustomTime.java | wa | public | public_config; shadow-write + dual-read |
| `seentick` | string | — | SeenTick.java | wa | public | public_config; shadow-write + dual-read |
| `segundos` | boolean | — | CustomTime.java | wa | public | public_config; shadow-write + dual-read |
| `selectable_message` | boolean | — | Others.java | wa | public | public_config; shadow-write + dual-read |
| `send_video_as_video_note` | boolean | — | VideoNoteAttachment.java | wa | public | public_config; shadow-write + dual-read |
| `separategroups` | boolean | — | BasePreferenceFragment.java<br>FeatureLoader.java<br>+4 | app+wa | public | public_config; shadow-write + dual-read |
| `separategroups_counter_type` | string | — | SeparateGroup.java | wa | public | public_config; shadow-write + dual-read |
| `show_dndmode` | boolean | — | MainActivity.java<br>MenuHome.java | app+wa | public | public_config; shadow-write + dual-read |
| `show_freezeLastSeen` | boolean | — | BasePreferenceFragment.java<br>EmbeddedBasePreferenceFragment.java<br>+1 | app+wa | public | public_config; shadow-write + dual-read |
| `show_hidereceipt` | boolean | — | MenuHome.java | wa | public | public_config; shadow-write + dual-read |
| `show_home_menu` | string | — | MenuHome.java | wa | public | public_config; shadow-write + dual-read |
| `show_hook_toast` | boolean | — | BasePreferenceFragment.java<br>FeatureLoader.java<br>+1 | app+wa | public | public_config; shadow-write + dual-read |
| `showbiohome` | boolean | — | CustomToolbar.java | wa | public | public_config; shadow-write + dual-read |
| `shownamehome` | boolean | — | CustomToolbar.java | wa | public | public_config; shadow-write + dual-read |
| `showonline` | boolean | — | Others.java | wa | public | public_config; shadow-write + dual-read |
| `showonlinetext` | boolean | — | BasePreferenceFragment.java<br>ShowOnline.java<br>+1 | app+wa | public | public_config; shadow-write + dual-read |
| `status_style` | string | — | BasePreferenceFragment.java<br>Others.java<br>+1 | app+wa | public | public_config; shadow-write + dual-read |
| `tasker` | boolean | — | Tasker.java | wa | public | public_config; shadow-write + dual-read |
| `toast_viewed_message` | boolean | — | ToastViewer.java | wa | public | public_config; shadow-write + dual-read |
| `toast_viewed_status` | boolean | — | ToastViewer.java | wa | public | public_config; shadow-write + dual-read |
| `toastdeleted` | boolean | — | AntiRevoke.java | wa | public | public_config; shadow-write + dual-read |
| `transcription_provider` | string | — | AudioTranscript.java | wa | public | public_config; shadow-write + dual-read |
| `typearchive` | string | — | CustomToolbar.java<br>HideChat.java | wa | public | public_config; shadow-write + dual-read |
| `update_check` | boolean | — | FeatureLoader.java | wa | public | public_config; shadow-write + dual-read |
| `video_maxfps` | boolean | — | MediaQuality.java | wa | public | public_config; shadow-write + dual-read |
| `video_real_resolution` | boolean | — | MediaQuality.java | wa | public | public_config; shadow-write + dual-read |
| `videoquality` | boolean | — | MediaQuality.java | wa | public | public_config; shadow-write + dual-read |
| `viewonce` | boolean | — | ViewOnce.java | wa | public | public_config; shadow-write + dual-read |
| `wae_color_preset` | string | — | BaseActivity.java<br>LocalDiagnostics.java<br>+1 | app+wa | public | public_config; shadow-write + dual-read |
| `wallpaper` | boolean | — | BasePreferenceFragment.java<br>WallpaperView.java<br>+2 | app+wa | public | public_config; shadow-write + dual-read |
| `assemblyai_key` | string | — | AudioTranscript.java | wa | secret | private_config; UID-validated provider read |
| `bootloader_spoofer_xml` | string | — | HookBL.java | wa | secret | private_config; UID-validated provider read |
| `groq_api_key` | string | — | AudioTranscript.java | wa | secret | private_config; UID-validated provider read |

## 5. Manifesto de remoção — funções sem implementação
As chaves abaixo são declaradas nas telas de Preferences (standalone e/ou embedded),
aparecem em `waex_settings_map.json` e têm strings traduzidas, mas **nenhum leitor em
todo o código Java**. São resquícios de UI de funções cuja implementação era fechada
(Pro/Helper) ou que nunca foram portadas da beta para a base estável 1.7.0.

Elas são removidas da UI no Bloco C para o app parar de prometer o que não faz.
**Este manifesto existe para permitir a reimplementação futura**: cada entrada registra
o texto exato que a função prometia ao usuário, as telas onde aparecia e os recursos de
string envolvidos. Nada aqui foi reimplementado; tudo precisa ser reescrito do zero,
sem copiar código fechado.

### `add_status_reply_menu_item` (boolean)

- **Prometia:** Add option to menu to open reply
- **Descrição:** Adds a "Reply" item in the playback three-dots menu to expand the reply panel manually
- **Telas:** embedded_settings_status.xml
- **Strings:** `add_status_reply_menu_item`, `add_status_reply_menu_item_sum`

### `always_typing_global_type` (string)

- **Prometia:** Global Simulated Status Kind
- **Descrição:** —
- **Telas:** fragment_privacy.xml
- **Strings:** `always_typing_global_type_title`

### `call_recording_calls_tab_menu` (boolean)

- **Prometia:** (texto literal no XML)
- **Descrição:** —
- **Telas:** embedded_settings_calls.xml, fragment_media.xml
- **Strings:** `call_recording_calls_tab_menu`, `call_recording_calls_tab_menu_summary`

### `delete_message_file` (boolean)

- **Prometia:** Delete message media file
- **Descrição:** Add a menu option to delete downloaded media files from user storage.
- **Telas:** embedded_settings_conversation.xml, preference_general_conversation.xml
- **Strings:** `delete_message_file`, `delete_message_file_sum`

### `delete_message_file_sent` (boolean)

- **Prometia:** Also apply for my sent messages
- **Descrição:** Show the delete option for your own sent media messages as well.
- **Telas:** embedded_settings_conversation.xml, preference_general_conversation.xml
- **Strings:** `delete_message_file_sent`, `delete_message_file_sent_sum`

### `disable_status_swipe_up` (boolean)

- **Prometia:** Disable Swipe Up to reply
- **Descrição:** Disables the swipe up gesture that opens the reply bottom sheet in status view screen
- **Telas:** embedded_settings_status.xml
- **Strings:** `disable_status_swipe_up`, `disable_status_swipe_up_sum`

### `enable_spy` (boolean)

- **Prometia:** Enable Spy Tool
- **Descrição:** Logs internal events to Xposed log for debugging.
- **Telas:** embedded_settings_status.xml, fragment_general.xml
- **Strings:** `enable_spy`, `enable_spy_sum`

### `file_size_spoofer` (boolean)

- **Prometia:** File Size Spoofer
- **Descrição:** Add a button in the send preview screen to spoof the displayed file size shown to the recipient
- **Telas:** embedded_settings_media.xml, fragment_media.xml
- **Strings:** `file_size_spoofer`, `file_size_spoofer_sum`

### `message_bomber` (boolean)

- **Prometia:** Message Bomber
- **Descrição:** Send multiple messages to a contact in rapid succession.
- **Telas:** embedded_settings_conversation.xml, preference_general_conversation.xml
- **Strings:** `message_bomber`, `message_bomber_sum`

### `pro_status_splitter` (boolean)

- **Prometia:** Status Video Splitter
- **Descrição:** Split long videos into 30/60/90 second clips for seamless posting on WhatsApp Status.
- **Telas:** embedded_settings_status.xml, fragment_media.xml
- **Strings:** `pro_status_splitter`, `pro_status_splitter_sum`

### `send_audio_as_voice_status` (boolean)

- **Prometia:** Send Audio as Voice Status
- **Descrição:** Enables picking and uploading local audio files as voice statuses
- **Telas:** embedded_settings_status.xml
- **Strings:** `send_audio_as_voice_status`, `send_audio_as_voice_status_sum`

### `status_bottom_play_pause_button` (boolean)

- **Prometia:** Show play/pause button in bottom bar
- **Descrição:** Adds a play/pause toggle button next to the heart reaction button at the bottom of status playback
- **Telas:** embedded_settings_status.xml
- **Strings:** `status_bottom_play_pause_button`, `status_bottom_play_pause_button_sum`

### `status_video_fast_gesture` (boolean)

- **Prometia:** Long press to fast forward/rewind
- **Descrição:** Long press the right side of a video status to fast-forward, or the left side to fast-rewind
- **Telas:** embedded_settings_status.xml
- **Strings:** `status_video_fast_gesture`, `status_video_fast_gesture_sum`

### `status_video_fast_speed` (string)

- **Prometia:** Playback speed
- **Descrição:** Choose the playback speed during fast forward/rewind
- **Telas:** embedded_settings_status.xml
- **Strings:** `status_video_fast_speed`, `status_video_fast_speed_sum`

### `statuscomposer` (boolean)

- **Prometia:** Custom colors for text status
- **Descrição:** Press and hold on the color selector in the status to customize it
- **Telas:** embedded_settings_status.xml, fragment_customization.xml
- **Strings:** `custom_colors_for_text_status`, `custom_colors_for_text_status_sum`


## 6. Superfície IPC e componentes exportados

Levantada de `app/src/main/AndroidManifest.xml` no commit base.

### 6.1 Providers

| Componente | Authority | `exported` | Permissão | Validação de caller | Operações |
|---|---|---|---|---|---|
| `xposed.bridge.providers.HookProvider` | `${applicationId}.hookprovider` | `true` | nenhuma | **nenhuma** | `get_preference`, `get_all_preferences`, `put_preference`, `remove_preference`, `clear_preferences`, `getHookBinder` |
| `provider.DeletedMessagesProvider` | `${applicationId}.provider` | `true` | nenhuma | **nenhuma** | `insert` em `deleted_messages`, `get_preference`, `put_preference`, `log_tasker_event` |
| `androidx.core.content.FileProvider` | `${applicationId}.fileprovider` | `false` | — | grants por URI | leitura de arquivos declarados em `xml/file_paths.xml` |

`HookProvider.call()` abre com `Binder.clearCallingIdentity()` e nunca consulta
`Binder.getCallingUid()`. Qualquer aplicativo instalado pode ler todas as preferências do
módulo, reescrevê-las ou apagá-las por inteiro, e obter o `HookBinder`.

`DeletedMessagesProvider` aceita `insert` de mensagens e `put_preference` sem qualquer
verificação, e `log_tasker_event` grava número de destino e prévia de mensagem no
histórico local a pedido de qualquer chamador.

### 6.2 Receivers

| Componente | Ação | `exported` | Permissão | Validação |
|---|---|---|---|---|
| `receivers.TaskerMessageSentReceiver` | `com.waenhancer.MESSAGE_SENT` | `true` | nenhuma | **nenhuma** |
| `receivers.WAFReceiver` | `com.facebook.GET_PHONE_ID`, `android.support.customtabs.action.CustomTabsService` | `true` | nenhuma | corpo vazio |
| `Tasker.SenderMessageBroadcastReceiver` (registrado em runtime no processo do WhatsApp) | `com.waenhancer.MESSAGE_SENT`, `com.waenhancer.MESSAGE_SENT_INTERNAL` | `RECEIVER_EXPORTED` | nenhuma | **nenhuma** |

### 6.3 Broadcasts emitidos

| Ação | Emissor | Explícito? | Conteúdo |
|---|---|---|---|
| `com.waenhancer.MESSAGE_RECEIVED` | `Tasker.hookReceiveMessage` | **não** | número de telefone, nome do contato, **texto completo da mensagem recebida** |
| `com.waenhancer.EVENT` | `Tasker.sendTaskerEvent` | **não** | nome, número, evento |
| `com.waenhancer.MESSAGE_SENT_INTERNAL` | `TaskerMessageSentReceiver.forwardBroadcast` | sim (`setPackage`) | número, mensagem |

Os dois primeiros são broadcasts implícitos sem permissão. Qualquer aplicativo que
declare um receiver para essas ações recebe o conteúdo de todas as mensagens que chegam
ao WhatsApp enquanto a integração Tasker estiver ligada.

As strings de ação são literais `com.waenhancer.*` fixas, e não derivadas de
`BuildConfig.APPLICATION_ID`, ao contrário das authorities.

### 6.4 Serviços e activities

- `xposed.bridge.service.BridgeService` — `exported="true"`, sem permissão.
- Dez `TileService` de Quick Settings — `exported="true"` com
  `android:permission="android.permission.BIND_QUICK_SETTINGS_TILE"`, que é o contrato
  correto do sistema.
- Activities `exported="true"` sem filtro que as justifique: `EmbeddedSettingsActivity`,
  `RecordingsActivity`, `ChangelogActivity`, `SupportedVersionsActivity`.
  `MainActivity` e `ForceStartActivity` têm `LAUNCHER`/`VIEW` e permanecem exportadas.

### 6.5 Permissões declaradas

`INTERNET`, `QUERY_ALL_PACKAGES`, `POST_NOTIFICATIONS`,
`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `SYSTEM_ALERT_WINDOW`, `READ_CONTACTS`,
`RECORD_AUDIO`, `REQUEST_INSTALL_PACKAGES`, `READ_EXTERNAL_STORAGE`,
`WRITE_EXTERNAL_STORAGE`, `READ_MEDIA_IMAGES`, `READ_MEDIA_AUDIO`, `READ_MEDIA_VIDEO`,
`MANAGE_EXTERNAL_STORAGE`.

`android:allowBackup="true"` — o backup do Android copia o arquivo de preferências,
incluindo hoje os três segredos da secção 3.

### 6.6 Processos e UIDs

| Processo | UID | Como lê as preferências |
|---|---|---|
| app do módulo (`com.waenhancer.community`) | UID próprio | `PreferenceManager.getDefaultSharedPreferences` |
| WhatsApp hookado (`com.whatsapp`) | UID do WhatsApp | `XSharedPreferences` sobre o arquivo world-readable |
| WhatsApp Business (`com.whatsapp.w4b`) | UID próprio do w4b | idem |
| qualquer app de terceiro | qualquer UID | arquivo world-readable **e** `HookProvider` exportado |

A última linha é a exposição que o Bloco C precisa fechar.

## 7. Contrato atual, invariantes e contrato alvo

### 7.1 Contrato atual

Existe um único store: o `SharedPreferences` padrão do app, tornado world-readable por
hook. Todo consumidor — UI do módulo, hooks no processo do WhatsApp, providers
exportados, backup e diagnóstico — lê e escreve nesse mesmo arquivo, sem distinção de
sensibilidade e sem validação de chamador.

### 7.2 Invariantes que qualquer migração deve preservar

1. O processo do WhatsApp continua obtendo todas as chaves `public` a cada
   `reload()`, sem depender de o app do módulo estar em execução.
2. Alterar uma configuração na UI continua refletindo no WhatsApp após reinício,
   pelo mesmo mecanismo de notificação já existente.
3. Nenhuma configuração existente do usuário é perdida em upgrade.
4. Backups legados seguros continuam importáveis.
5. Nenhuma escrita destrutiva ocorre antes de validação integral; `clear()` não é
   usado como etapa de migração nem de importação.
6. As invariantes pós-Codex de A+B seguem valendo: nenhum XML referencia classes Pro
   removidas, targets runtime usam `BuildConfig.APPLICATION_ID`, salvar tema inativo
   não o ativa, o menu CSS não tem fall-through, URLs usam `igorcv88/WaEnhancerX`.

### 7.3 Contrato alvo

Dois stores:

- **`public_config`** — arquivo world-readable, exclusivamente chaves classificadas
  `public`. É o único arquivo que `XSharedPreferences` passa a ler.
- **`private_config`** — `MODE_PRIVATE`, acessível apenas ao UID do módulo. Recebe
  `private`, `secret`, `cache`, `runtime`.

Segredos exigidos por hooks são obtidos sob demanda por chamada de provider validada por
UID, mantidos apenas em memória no processo do WhatsApp e nunca gravados em
`public_config`.

### 7.4 Migração, compatibilidade e rollback

Sequência obrigatória, conforme secção 3.4 do plano de execução:

1. detectar a estrutura antiga e sua versão;
2. gravar snapshot em `files/migration_snapshots/`;
3. validar leitura da origem;
4. escrever em `public_config`/`private_config` **sem apagar** o store legado;
5. validar contagens e tipos por chave;
6. alternar a leitura para a nova estrutura;
7. manter dual-read com fallback legado por pelo menos uma release estável;
8. só então oferecer limpeza, nunca automática.

**Rollback:** enquanto o store legado existir, restaurá-lo é apenas voltar a preferi-lo
na leitura; o snapshot cobre o caso de escrita parcial. **Downgrade:** uma versão
anterior do módulo continua lendo o store legado intacto, portanto um downgrade não
perde configuração. **Falha:** se a migração não completar, a leitura permanece no store
legado e a tentativa é registrada localmente de forma redigida.
