package com.waenhancer.xposed.features.general;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.waenhancer.xposed.core.Feature;
import com.waenhancer.xposed.core.WppCore;
import com.waenhancer.R;
import com.waenhancer.xposed.utils.Utils;

import com.waenhancer.xposed.core.components.AlertDialogWpp;
import de.robv.android.xposed.XC_MethodHook;
import android.content.SharedPreferences;
import de.robv.android.xposed.XposedHelpers;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class NewChat extends Feature {

    private static final Map<String, String> CC_TO_ISO = new HashMap<>();
    static {
        String data = "1:US,7:RU,20:EG,27:ZA,30:GR,31:NL,32:BE,33:FR,34:ES,36:HU,39:IT,40:RO,41:CH,43:AT,44:GB,45:DK,46:SE,47:NO,48:PL,49:DE,51:PE,52:MX,53:CU,54:AR,55:BR,56:CL,57:CO,58:VE,60:MY,61:AU,62:ID,63:PH,64:NZ,65:SG,66:TH,81:JP,82:KR,84:VN,86:CN,90:TR,91:IN,92:PK,93:AF,94:LK,95:MM,98:IR,212:MA,213:DZ,216:TN,218:LY,220:GM,221:SN,222:MR,223:ML,224:GN,225:CI,226:BF,227:NE,228:TG,229:BJ,230:MU,231:LR,232:SL,233:GH,234:NG,235:TD,236:CF,237:CM,238:CV,239:ST,240:GQ,241:GA,242:CG,243:CD,244:AO,245:GW,246:IO,248:SC,249:SD,250:RW,251:ET,252:SO,253:DJ,254:KE,255:TZ,256:UG,257:BI,258:MZ,260:ZM,261:MG,262:RE,263:ZW,264:NA,265:MW,266:LS,267:BW,268:SZ,269:KM,290:SH,291:ER,297:AW,298:FO,299:GL,350:GI,351:PT,352:LU,353:IE,354:IS,355:AL,356:MT,357:CY,358:FI,359:BG,370:LT,371:LV,372:EE,373:MD,374:AM,375:BY,376:AD,377:MC,378:SM,380:UA,381:RS,382:ME,385:HR,386:SI,387:BA,389:MK,420:CZ,421:SK,423:LI,500:FK,501:BZ,502:GT,503:SV,504:HN,505:NI,506:CR,507:PA,508:PM,509:HT,590:GP,591:BO,592:GY,593:EC,594:GF,595:PY,596:MQ,597:SR,598:UY,599:CW,670:TL,672:NF,673:BN,674:NR,675:PG,676:TO,677:SB,678:VU,679:FJ,680:PW,681:WF,682:CK,683:NU,685:WS,686:KI,687:NC,688:TV,689:PF,690:TK,691:FM,692:MH,850:KP,852:HK,853:MO,855:KH,856:LA,880:BD,886:TW,960:MV,961:LB,962:JO,963:SY,964:IQ,965:KW,966:SA,967:YE,968:OM,970:PS,971:AE,972:IL,973:BH,974:QA,975:BT,976:MN,977:NP,992:TJ,993:TM,994:AZ,995:GE,996:KG,998:UZ";
        for (String part : data.split(",")) {
            String[] kv = part.split(":");
            CC_TO_ISO.put(kv[0], kv[1]);
        }
    }

    private static final int REQUEST_CODE_COUNTRY_PICKER = 9909;

    private static WeakReference<TextView> activeCountryTextRef;
    private static WeakReference<EditText> activeCcEditRef;
    private static String activeIso = "US";
    private static boolean isSelfUpdating = false;

    public NewChat(@NonNull ClassLoader loader, @NonNull SharedPreferences preferences) {
        super(loader, preferences);
    }

    @Override
    public void doHook() {
        if (!prefs.getBoolean("newchat", true)) return;

        // Hook HomeActivity onResume to bind long press to the FAB
        XposedHelpers.findAndHookMethod(
            WppCore.getHomeActivityClass(classLoader),
            "onResume",
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    final Activity activity = (Activity) param.thisObject;
                    int fabId = activity.getResources().getIdentifier("fab", "id", activity.getPackageName());
                    if (fabId != 0) {
                        final View fab = activity.findViewById(fabId);
                        if (fab != null) {
                            fab.setOnLongClickListener(new View.OnLongClickListener() {
                                @Override
                                public boolean onLongClick(View v) {
                                    triggerNewChat(activity);
                                    return true;
                                }
                            });
                        }
                    }
                }
            }
        );

        // Hook HomeActivity onActivityResult to handle country selection
        XposedHelpers.findAndHookMethod(
            WppCore.getHomeActivityClass(classLoader),
            "onActivityResult",
            int.class,
            int.class,
            Intent.class,
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    int requestCode = (int) param.args[0];
                    int resultCode = (int) param.args[1];
                    Intent data = (Intent) param.args[2];
                    if (requestCode == REQUEST_CODE_COUNTRY_PICKER && resultCode == Activity.RESULT_OK && data != null) {
                        String countryName = data.getStringExtra("country_name");
                        String cc = data.getStringExtra("cc");
                        String iso = data.getStringExtra("iso");
                        updateActiveDialog(countryName, cc, iso);
                    }
                }
            }
        );
    }

    private static void updateActiveDialog(String countryName, String cc, String iso) {
        if (iso != null) {
            activeIso = iso;
        }
        if (activeCountryTextRef != null) {
            TextView tv = activeCountryTextRef.get();
            if (tv != null && countryName != null) {
                String flag = getFlagEmoji(activeIso);
                tv.setText(flag + "   " + countryName);
            }
        }
        if (activeCcEditRef != null) {
            EditText et = activeCcEditRef.get();
            if (et != null && cc != null) {
                isSelfUpdating = true;
                try {
                    et.setText("+" + cc);
                } finally {
                    isSelfUpdating = false;
                }
            }
        }
    }

    public static String getCountryName(String iso) {
        if (iso == null) return null;
        Locale locale = new Locale("", iso);
        return locale.getDisplayName(Locale.getDefault());
    }

    public static String getFlagEmoji(String countryIsoCode) {
        if (countryIsoCode == null || countryIsoCode.length() != 2) {
            return "";
        }
        try {
            int firstChar = Character.codePointAt(countryIsoCode.toUpperCase(), 0) - 0x41 + 0x1F1E6;
            int secondChar = Character.codePointAt(countryIsoCode.toUpperCase(), 1) - 0x41 + 0x1F1E6;
            return new String(Character.toChars(firstChar)) + new String(Character.toChars(secondChar));
        } catch (Throwable t) {
            return "";
        }
    }

    public static void triggerNewChat(final Activity activity) {
        float density = activity.getResources().getDisplayMetrics().density;
        
        // 1. Detect default country from SIM/Device
        String defaultIso = "US";
        String defaultCc = "1";
        try {
            android.telephony.TelephonyManager tm = (android.telephony.TelephonyManager) activity.getSystemService(android.content.Context.TELEPHONY_SERVICE);
            if (tm != null) {
                String simCountry = tm.getSimCountryIso();
                if (simCountry != null && !simCountry.isEmpty()) {
                    defaultIso = simCountry.toUpperCase();
                    for (Map.Entry<String, String> entry : CC_TO_ISO.entrySet()) {
                        if (entry.getValue().equalsIgnoreCase(defaultIso)) {
                            defaultCc = entry.getKey();
                            break;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}

        activeIso = defaultIso;

        // 2. Create body layout
        LinearLayout bodyContainer = new LinearLayout(activity);
        bodyContainer.setOrientation(LinearLayout.VERTICAL);
        int bodyPadding = (int) (8 * density);
        bodyContainer.setPadding(bodyPadding, bodyPadding, bodyPadding, bodyPadding);
        bodyContainer.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        // Country Picker Header (Clickable card with Flag + Name)
        final TextView tvCountry = new TextView(activity);
        String initialFlag = getFlagEmoji(activeIso);
        tvCountry.setText(initialFlag + "   " + getCountryName(activeIso));
        tvCountry.setTextSize(16);
        tvCountry.setTextColor(0xffffffff);
        tvCountry.setGravity(Gravity.CENTER_VERTICAL);
        
        android.graphics.drawable.GradientDrawable selectorBg = new android.graphics.drawable.GradientDrawable();
        selectorBg.setCornerRadius(8 * density);
        selectorBg.setColor(android.graphics.Color.parseColor("#1f8696a0"));
        tvCountry.setBackground(selectorBg);
        int padPx = (int) (14 * density);
        tvCountry.setPadding(padPx, padPx, padPx, padPx);
        
        LinearLayout.LayoutParams countryLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        countryLp.bottomMargin = (int) (16 * density);
        tvCountry.setLayoutParams(countryLp);
        
        tvCountry.setOnClickListener(v -> {
            try {
                Intent intent = new Intent();
                intent.setClassName(activity.getPackageName(), "com.whatsapp.accountdelete.phonematching.CountryPicker");
                intent.putExtra("country_iso", activeIso);
                intent.putExtra("country_display_name", tvCountry.getText().toString());
                activity.startActivityForResult(intent, REQUEST_CODE_COUNTRY_PICKER);
            } catch (Throwable t) {
                Utils.showToast("Error opening country picker", android.widget.Toast.LENGTH_SHORT);
            }
        });

        bodyContainer.addView(tvCountry);
        activeCountryTextRef = new WeakReference<>(tvCountry);

        // Phone Number Input Row (CC + Phone)
        LinearLayout phoneRow = new LinearLayout(activity);
        phoneRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        phoneRow.setLayoutParams(rowLp);

        // Country Code Input
        final EditText edtCc = new EditText(activity);
        edtCc.setText("+" + defaultCc);
        edtCc.setInputType(InputType.TYPE_CLASS_PHONE);
        edtCc.setTextColor(0xffffffff);
        edtCc.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams ccLp = new LinearLayout.LayoutParams(
            (int) (70 * density),
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        ccLp.rightMargin = (int) (12 * density);
        edtCc.setLayoutParams(ccLp);

        // Phone Number Input
        final EditText edtPhone = new EditText(activity);
        edtPhone.setHint(getCountryHint(activity, defaultCc, activeIso));
        edtPhone.setInputType(InputType.TYPE_CLASS_PHONE);
        edtPhone.setTextColor(0xffffffff);
        edtPhone.setHintTextColor(0xff8696a0);
        LinearLayout.LayoutParams phoneLp = new LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1.0f
        );
        edtPhone.setLayoutParams(phoneLp);

        phoneRow.addView(edtCc);
        phoneRow.addView(edtPhone);
        bodyContainer.addView(phoneRow);

        activeCcEditRef = new WeakReference<>(edtCc);

        // Auto-update country name & flag when typing country code
        edtCc.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                if (isSelfUpdating) return;
                String text = s.toString();
                if (!text.startsWith("+")) {
                    isSelfUpdating = true;
                    if (text.isEmpty()) {
                        edtCc.setText("+");
                        edtCc.setSelection(1);
                    } else {
                        String clean = text.replaceAll("\\+", "");
                        edtCc.setText("+" + clean);
                        edtCc.setSelection(edtCc.getText().length());
                    }
                    isSelfUpdating = false;
                    return;
                }

                String cc = text.substring(1);
                if (CC_TO_ISO.containsKey(cc)) {
                    String iso = CC_TO_ISO.get(cc);
                    String name = getCountryName(iso);
                    if (name != null) {
                        activeIso = iso;
                        String flag = getFlagEmoji(iso);
                        tvCountry.setText(flag + "   " + name);
                    }
                }
                edtPhone.setHint(getCountryHint(activity, cc, activeIso));
                edtPhone.setText(edtPhone.getText()); // Trigger real-time validation for the new country code
            }
        });

        final TextView[] btnMessageRef = new TextView[1];

        // Real-time validation: watch the phone number field and update button + error state
        edtPhone.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                String phone = s.toString().replaceAll("[+\\-()/\\s]", "");
                TextView btn = btnMessageRef[0];

                if (phone.isEmpty()) {
                    edtPhone.setError(null);
                    if (btn != null) {
                        // Keep button disabled while field is empty (if validator is present)
                        boolean hasValidator = isValidatorLoaded(activity);
                        btn.setEnabled(!hasValidator);
                        btn.setAlpha(hasValidator ? 0.5f : 1.0f);
                    }
                    return;
                }

                String cc = edtCc.getText().toString().replaceAll("\\+", "");
                String fullNumber = cc + phone;

                boolean validatorPresent = false;
                boolean isValid = true;

                try {
                    ClassLoader proClassLoader = com.waenhancer.xposed.utils.ProHelper.getPluginClassLoader(activity);
                    if (proClassLoader != null) {
                        Class<?> validatorClass = proClassLoader.loadClass("com.waex.pro.utils.PhoneNumberValidator");
                        validatorPresent = true;

                        // Find the phone-code regex key that matches the selected country code
                        java.lang.reflect.Method phoneCodeRegexesMethod = validatorClass.getMethod("phoneCodeRegexes");
                        java.util.Set<String> keys = (java.util.Set<String>) phoneCodeRegexesMethod.invoke(null);

                        String matchingKey = null;
                        if (keys != null) {
                            for (String key : keys) {
                                try {
                                    if (java.util.regex.Pattern.compile(key).matcher("+" + cc).matches()) {
                                        matchingKey = key;
                                        break;
                                    }
                                } catch (Throwable ignored) {}
                            }
                        }

                        boolean validated = false;
                        if (matchingKey != null) {
                            java.lang.reflect.Method isValidForPhoneCodeRegexMethod = validatorClass.getMethod(
                                "isValidForPhoneCodeRegex", String.class, String.class
                            );
                            isValid = (boolean) isValidForPhoneCodeRegexMethod.invoke(null, fullNumber, matchingKey);
                            validated = true;
                        }

                        if (!validated) {
                            // Fallback: validate by English country name
                            String englishCountryName = new java.util.Locale("", activeIso).getDisplayCountry(java.util.Locale.ENGLISH);
                            java.lang.reflect.Method isValidForCountryMethod = validatorClass.getMethod(
                                "isValidForCountry", String.class, String.class, boolean.class
                            );
                            isValid = (boolean) isValidForCountryMethod.invoke(null, fullNumber, englishCountryName, true);
                        }
                    }
                } catch (ClassNotFoundException | java.lang.reflect.InvocationTargetException ignored) {
                    // Pro validator not installed / not accessible — allow without validation
                    validatorPresent = false;
                    isValid = true;
                } catch (Throwable ignored) {
                    // Other reflection errors — treat as unknown, don't block the user
                    isValid = true;
                }

                // Update error display: always show error immediately for invalid input
                if (!validatorPresent || isValid) {
                    edtPhone.setError(null);
                } else {
                    edtPhone.setError("Invalid phone number for this country");
                }

                // Update button state
                if (btn != null) {
                    boolean enable = !validatorPresent || isValid;
                    btn.setEnabled(enable);
                    btn.setAlpha(enable ? 1.0f : 0.5f);
                }
            }
        });

        // 3. Create and show AlertDialogWpp bottom sheet
        AlertDialogWpp alert = new AlertDialogWpp(activity);
        alert.setTitle("New Chat");
        alert.setBottomSheet(true);
        alert.setFullHeight(true);
        alert.setView(bodyContainer);

        alert.setPositiveButton("Message", null);
        alert.setNegativeButton("Cancel", (dialogInterface, which) -> dialogInterface.dismiss());

        android.app.Dialog dialog = alert.show();
        if (dialog != null) {
            // Use the direct reference exposed by AlertDialogWpp instead of searching by text,
            // which is unreliable (button text can be uppercased, button can be a non-TextView etc.).
            TextView btnMessage = alert.getPositiveButton();
            btnMessageRef[0] = btnMessage;

            if (btnMessage != null) {
                // Disable immediately while phone field is still empty (validator is present)
                if (isValidatorLoaded(activity)) {
                    btnMessage.setEnabled(false);
                    btnMessage.setAlpha(0.5f);
                }

                btnMessage.setOnClickListener(v -> {
                    String cc = edtCc.getText().toString().replaceAll("\\+", "");
                    String phone = edtPhone.getText().toString().replaceAll("[+\\-()/\\s]", "");

                    if (phone.isEmpty()) {
                        edtPhone.setError("Phone number cannot be empty");
                        return;
                    }

                    String fullNumber = cc + phone;
                    boolean isValid = true;

                    // Final validation guard before starting the chat
                    try {
                        ClassLoader proClassLoader = com.waenhancer.xposed.utils.ProHelper.getPluginClassLoader(activity);
                        if (proClassLoader != null) {
                            Class<?> validatorClass = proClassLoader.loadClass("com.waex.pro.utils.PhoneNumberValidator");

                            java.lang.reflect.Method phoneCodeRegexesMethod = validatorClass.getMethod("phoneCodeRegexes");
                            java.util.Set<String> keys = (java.util.Set<String>) phoneCodeRegexesMethod.invoke(null);

                            String matchingKey = null;
                            if (keys != null) {
                                for (String key : keys) {
                                    try {
                                        if (java.util.regex.Pattern.compile(key).matcher("+" + cc).matches()) {
                                            matchingKey = key;
                                            break;
                                        }
                                    } catch (Throwable ignored) {}
                                }
                            }

                            boolean validated = false;
                            if (matchingKey != null) {
                                java.lang.reflect.Method isValidForPhoneCodeRegexMethod = validatorClass.getMethod(
                                    "isValidForPhoneCodeRegex", String.class, String.class
                                );
                                isValid = (boolean) isValidForPhoneCodeRegexMethod.invoke(null, fullNumber, matchingKey);
                                validated = true;
                            }

                            if (!validated) {
                                String englishCountryName = new java.util.Locale("", activeIso).getDisplayCountry(java.util.Locale.ENGLISH);
                                java.lang.reflect.Method isValidForCountryMethod = validatorClass.getMethod(
                                    "isValidForCountry", String.class, String.class, boolean.class
                                );
                                isValid = (boolean) isValidForCountryMethod.invoke(null, fullNumber, englishCountryName, true);
                            }
                        }
                    } catch (Throwable t) {
                        // Validator not available — allow without validation
                        isValid = true;
                    }

                    if (!isValid) {
                        edtPhone.setError("Invalid phone number for this country");
                        return;
                    }

                    // Valid — dismiss and open chat
                    dialog.dismiss();
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setData(Uri.parse("https://wa.me/" + fullNumber));
                    intent.setPackage(Utils.getApplication().getPackageName());
                    activity.startActivity(intent);
                });
            }
        }
    }


    private static boolean isValidatorLoaded(Activity activity) {
        try {
            ClassLoader proClassLoader = com.waenhancer.xposed.utils.ProHelper.getPluginClassLoader(activity);
            if (proClassLoader != null) {
                proClassLoader.loadClass("com.waex.pro.utils.PhoneNumberValidator");
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static String getCountryHint(Activity activity, String cc, String activeIso) {
        try {
            ClassLoader proClassLoader = com.waenhancer.xposed.utils.ProHelper.getPluginClassLoader(activity);
            if (proClassLoader != null) {
                Class<?> validatorClass = proClassLoader.loadClass("com.waex.pro.utils.PhoneNumberValidator");
                
                java.lang.reflect.Method phoneCodeRegexesMethod = validatorClass.getMethod("phoneCodeRegexes");
                java.util.Set<String> keys = (java.util.Set<String>) phoneCodeRegexesMethod.invoke(null);
                
                String matchingKey = null;
                if (keys != null) {
                    for (String key : keys) {
                        try {
                            if (java.util.regex.Pattern.compile(key).matcher("+" + cc).matches()) {
                                matchingKey = key;
                                break;
                            }
                        } catch (Throwable ignored) {}
                    }
                }

                if (matchingKey != null) {
                    java.lang.reflect.Method countriesForPhoneCodeRegexMethod = validatorClass.getMethod(
                        "countriesForPhoneCodeRegex", String.class
                    );
                    java.util.List<?> countryPatterns = (java.util.List<?>) countriesForPhoneCodeRegexMethod.invoke(null, matchingKey);
                    if (countryPatterns != null && !countryPatterns.isEmpty()) {
                        Object countryPattern = countryPatterns.get(0);
                        java.lang.reflect.Method getPhonePatternMethod = countryPattern.getClass().getMethod("getPhonePattern");
                        String phonePattern = (String) getPhonePatternMethod.invoke(countryPattern);
                        return formatPatternToHint(phonePattern);
                    }
                }
            }
        } catch (Throwable ignored) {}
        return "Phone Number";
    }

    private static String formatPatternToHint(String pattern) {
        if (pattern == null || pattern.isEmpty()) return "Phone Number";
        
        // Clean up anchors
        String clean = pattern.replace("^", "").replace("$", "");
        
        // Remove optional country prefix groups e.g. ((00|\+)?92|0)?
        clean = clean.replaceAll("^\\([^)]*\\)\\??", "");
        clean = clean.replaceAll("^\\?\\([^)]*\\)\\??", "");
        
        // Replace character classes inside brackets like [0-6] with a single 'x'
        clean = clean.replaceAll("\\[.*?\\]", "x");
        
        // Convert \d to x
        clean = clean.replace("\\d", "x");
        
        // Convert x{N} to N 'x's
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("x\\{(\\d+)\\}").matcher(clean);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            int count = Integer.parseInt(matcher.group(1));
            matcher.appendReplacement(sb, "x".repeat(count));
        }
        matcher.appendTail(sb);
        clean = sb.toString();
        
        // Clean up brackets, parentheses and other regex symbols
        clean = clean.replace("?", "")
                     .replace("!", "")
                     .replace("(", "")
                     .replace(")", "");
        
        return clean;
    }

    @NonNull
    @Override
    public String getPluginName() {
        return "New Chat";
    }
}
