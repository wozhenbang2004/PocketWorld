package dev.pocketengine.displayhelper;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Narrow parser for the explicitly supported dumpsys activity format, fail closed otherwise. */
public final class PrimaryAppGuard {
    private static final Pattern DISPLAY=Pattern.compile("^Display #([0-9]+)(?:\\s.*)?$");
    private PrimaryAppGuard() { }
    public static boolean uses(String dump,String packageName) {
        if(dump==null||packageName==null)throw new IllegalArgumentException("Missing task ownership evidence");
        boolean primaryFound=false,primary=false,resumedFound=false,occupied=false;
        for(String line:dump.split("\n")){
            String text=line.trim();Matcher display=DISPLAY.matcher(text);
            if(display.matches()){primary="0".equals(display.group(1));primaryFound|=primary;continue;}
            if(primary&&(text.contains("mResumedActivity")||text.contains("topResumedActivity"))){resumedFound=true;if(text.contains(packageName+"/"))occupied=true;}
        }
        if(!primaryFound||!resumedFound)throw new IllegalArgumentException("Unrecognized primary task ownership");
        return occupied;
    }
}
