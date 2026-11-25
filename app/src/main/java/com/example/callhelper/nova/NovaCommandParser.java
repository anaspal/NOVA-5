package com.example.callhelper.nova;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class NovaCommandParser {
    private NovaCommandParser() {}
    public static String normalize(String input) {
        if(input==null)return "";
        String s=Normalizer.normalize(input,Normalizer.Form.NFD).replaceAll("\\p{M}+","")
          .replace('أ','ا').replace('إ','ا').replace('آ','ا').replace('ى','ي')
          .replace('ؤ','و').replace('ئ','ي').toLowerCase(Locale.ROOT);
        return s.replaceAll("[،,!?؟؛;.\\-]"," ").replaceAll("\\s+"," ").trim();
    }
    public static boolean containsWakeWord(String text){return normalize(text).matches(".*(^|\\s)(نوفا|نوفه|نوفي|نوفاا|نوڤا|نيوفا|نوفى|نفا|نوف|nova|nova|nofa)(\\s|$).*");}
    public static String removeWakeWord(String text){return normalize(text).replaceAll("(^|\\s)(نوفا|نوفه|نوفي|نوفاا|نوڤا|نيوفا|نوفى|نفا|نوف|nova|nova|nofa)(?=\\s|$)"," ").replaceAll("\\s+"," ").trim();}
    public static NovaCommand parse(String text) {
        String raw=stripWakeWordRaw(text);
        String c=normalize(raw);
        if(c.isEmpty())return result(NovaCommand.Type.WAKE_ONLY,text,"");
        String[] parts=c.split("\\s+(?:ثم|وبعدها|بعدها)\\s+");
        String[] rawParts=raw.split("\\s+(?:ثم|وبعدها|بعدها)\\s+");
        if(parts.length>1){
            if(parts.length>4)return result(NovaCommand.Type.UNKNOWN,text,"قسّم الطلب إلى أربع خطوات أو أقل");
            List<String> steps=new ArrayList<>();
            for(String part:parts){
                NovaCommand one=parseSingle(part,text,rawParts.length==parts.length?rawParts[steps.size()]:part);
                if(one.type==NovaCommand.Type.UNKNOWN||one.type==NovaCommand.Type.MULTI_STEP||one.type==NovaCommand.Type.WAKE_ONLY)
                    return result(NovaCommand.Type.UNKNOWN,text,"لم أستطع تحديد كل الخطوات بوضوح");
                steps.add(one.type.name()+"|"+one.argument);
            }
            return result(NovaCommand.Type.MULTI_STEP,text,join(steps));
        }
        return parseSingle(c,text,raw);
    }
    private static String stripWakeWordRaw(String text) {
        if(text==null)return "";
        return text.replaceFirst("(?i)(^|\\s)(نوفا|نوفه|نوفي|نوفاا|نوڤا|نيوفا|نوفى|نفا|نوف|nova|nova|nofa)(?=\\s|$)"," ").trim();
    }
    private static NovaCommand parseSingle(String c,String original,String raw){
        if(c.startsWith("اضغطي علي ")||c.startsWith("اضغط علي ")||c.startsWith("اضغطي على ")||c.startsWith("اضغط على ")||c.startsWith("اكبسي علي ")||c.startsWith("اكبس علي ")||c.startsWith("اكبسي على ")||c.startsWith("اكبس على ")||c.startsWith("اختاري ")||c.startsWith("اختار ")){
            String target=c.replaceFirst("^(اضغطي علي|اضغط علي|اضغطي على|اضغط على|اكبسي علي|اكبس علي|اكبسي على|اكبس على|اختاري|اختار)\\s*","");
            target=target.replaceFirst("^(زر|ايقونة|عنصر)\\s*","");
            return target.isEmpty()?result(NovaCommand.Type.UNKNOWN,original,"اذكر اسم العنصر"):result(NovaCommand.Type.CLICK_TEXT,original,target);
        }
        if(c.startsWith("اكتبي ")||c.startsWith("اكتب ")||c.startsWith("املي ")){
            String value=raw.replaceFirst("^(اكتبي|اكتب|املي)\\s*","").trim();
            return value.isEmpty()?result(NovaCommand.Type.UNKNOWN,original,"ما النص الذي تريد كتابته؟"):result(NovaCommand.Type.TYPE_TEXT,original,value);
        }
        if(has(c,"شغل الكشاف","شغلي الكشاف","ولع الفلاش","ولعي الفلاش","شغلي الفلاش","افتح الكشاف","شغل الضوء","شغلي الضوء","نورلي","نوريلي","شغللي الضوء"))return result(NovaCommand.Type.FLASHLIGHT_ON,original,"");
        if(has(c,"اطفئ الكشاف","اطفي الكشاف","اطفئ الفلاش","اطفي الفلاش","سكر الكشاف","اغلق الكشاف","طفي الضوء","طفي الفلاش","اطفيلي الكشاف"))return result(NovaCommand.Type.FLASHLIGHT_OFF,original,"");
        if(has(c,"كم الساعة","الساعة كم","احكيلي الوقت","ما الوقت","كم الوقت","قديش الساعة","شو الوقت"))return result(NovaCommand.Type.TIME,original,"");
        if(has(c,"كم البطارية","نسبة البطارية","شحن الجوال","كم الشحن","قديش البطارية","كم نسبة الشحن","قديش شحن التلفون"))return result(NovaCommand.Type.BATTERY,original,"");
        if(has(c,"ارفع الصوت","ارفعي الصوت","علي الصوت","زيد الصوت","زيدي الصوت","كبري الصوت","ارفعلي الصوت"))return result(NovaCommand.Type.VOLUME_UP,original,"");
        if(has(c,"اخفض الصوت","اخفضي الصوت","وطي الصوت","قلل الصوت","قللي الصوت","نزل الصوت","خففي الصوت","نقصي الصوت"))return result(NovaCommand.Type.VOLUME_DOWN,original,"");
        if(has(c,"اقرا الاشعارات","اقرئي الاشعارات","اقرا لي الاشعارات","شو الاشعارات","اقرا اخر اشعار","في اشعارات","شو عندي اشعارات"))return result(NovaCommand.Type.READ_NOTIFICATIONS,original,"");
        if(has(c,"افتح الاشعارات","افتحي الاشعارات","اسحب الاشعارات","لوحة الاشعارات"))return result(NovaCommand.Type.OPEN_NOTIFICATIONS,original,"");
        if(has(c,"الاعدادات السريعة","افتح الاعدادات السريعة","افتحي الاعدادات السريعة","لوحة التحكم"))return result(NovaCommand.Type.QUICK_SETTINGS,original,"");
        if(has(c,"التطبيقات الاخيرة","افتح التطبيقات الاخيرة","افتحي التطبيقات الاخيرة","المهام الاخيرة","قائمة التطبيقات"))return result(NovaCommand.Type.RECENTS,original,"");
        if(has(c,"انزلي لتحت","انزل لتحت","انزلي","انزل","مرري لتحت","اسفل"))return result(NovaCommand.Type.SCROLL_DOWN,original,"");
        if(has(c,"اطلعي لفوق","اطلع لفوق","اطلعي","اطلع","مرري لفوق","اعلى"))return result(NovaCommand.Type.SCROLL_UP,original,"");
        if(has(c,"اوقفي الاستماع","وقف الاستماع","توقفي عن الاستماع","اسكتي يا نوفا"))return result(NovaCommand.Type.STOP_LISTENING,original,"");
        if(has(c,"استأنفي الاستماع","استأنف الاستماع","ارجعي استمعي","كملي الاستماع","تابعي الاستماع"))return result(NovaCommand.Type.RESUME_LISTENING,original,"");
        if(has(c,"اكد ارسال الرسالة","اكد الارسال","انا اؤكد ارسال الرسالة","اؤكد ارسال الرسالة","موافق ارسل الرسالة"))
            return result(NovaCommand.Type.CONFIRM_SEND,original,"");
        if(c.matches("^(اتصلي|اتصل|كلمي|كلم|دقي|دق)\\s+.+")){
            String name=c.replaceFirst("^(اتصلي|اتصل|كلمي|كلم|دقي|دق)\\s+","").replaceFirst("^(علي|على|ب)\\s*","").trim();
            return name.isEmpty()?result(NovaCommand.Type.UNKNOWN,original,"بمن تريد الاتصال؟"):result(NovaCommand.Type.CALL_CONTACT,original,name);
        }
        if(has(c,"لقطة شاشة","لقطه شاشة","صوري الشاشة","صور الشاشة","سكرين شوت","خذي لقطة","خد لقطة"))return result(NovaCommand.Type.SCREENSHOT,original,"");
        if(has(c,"اقفلي الشاشة","اقفل الشاشة","قفلي الشاشة","قفل الشاشة","اطفي الشاشة","اطفئ الشاشة","طفي الشاشة"))return result(NovaCommand.Type.LOCK_SCREEN,original,"");
        if(has(c,"واي فاي","وايفاي","wifi"))return result(NovaCommand.Type.OPEN_WIFI,original,"");
        if(has(c,"بلوتوث","بلوتوس","بلوتوت","bluetooth"))return result(NovaCommand.Type.OPEN_BLUETOOTH,original,"");
        if(has(c,"شو الاوامر","ما هي الاوامر","ايش الاوامر","شو بتقدري","شو بتقدر","مساعدة","ساعديني","شو بتعرفي"))return result(NovaCommand.Type.HELP,original,"");
        if(has(c,"كرري","كرر","عيدي","اعيدي","شو قلتي","ماذا قلتي"))return result(NovaCommand.Type.REPEAT_LAST,original,"");
        if(has(c,"الشاشة الرئيسية","ارجع للرئيسية","ارجعي للرئيسية","الرئيسية","روح عالرئيسية"))return result(NovaCommand.Type.HOME,original,"");
        if(has(c,"ارجع للخلف","ارجعي للخلف","رجوع","ارجع","ارجعي","ارجع خطوة"))return result(NovaCommand.Type.BACK,original,"");
        if(c.contains("منبه")||c.contains("المنبه")||c.contains("نبهني"))return result(NovaCommand.Type.SET_ALARM,original,c);
        if(c.startsWith("افتح ")||c.startsWith("افتحي ")||c.startsWith("افتحلي ")||c.startsWith("شغل تطبيق ")||c.startsWith("شغلي تطبيق ")||c.startsWith("شغل ")||c.startsWith("شغلي ")||c.startsWith("روح علي ")||c.startsWith("روحي علي ")||c.startsWith("روح على ")||c.startsWith("روحي على ")){
            String app=c.replaceFirst("^(افتحلي|افتحي|افتح|شغلي? تطبيق|شغلي?|روح علي|روحي علي|روح على|روحي على)\\s*","");
            return result(NovaCommand.Type.OPEN_APP,original,app);
        }
        return result(NovaCommand.Type.UNKNOWN,original,c);
    }
    private static boolean has(String s,String...p){for(String x:p)if(s.contains(normalize(x)))return true;return false;}
    private static NovaCommand result(NovaCommand.Type t,String o,String a){return new NovaCommand(t,o,a);}
    private static String join(List<String> v){StringBuilder b=new StringBuilder();for(String s:v){if(b.length()>0)b.append(";;");b.append(s);}return b.toString();}
}
