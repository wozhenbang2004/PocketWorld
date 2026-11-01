package dev.pocketengine.features.attachments.processing;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.attachments.OwnerFilePreviewSource;
import java.io.File;
import org.json.JSONObject;

/** Closed native review alternatives; generic JSON cannot choose an executable or mint a source. */
public final class NativeFileProcess {
    public static final class Review {
        private final NativeImageTransform.Review image;private final NativeMediaTrim.Review trim;
        public final String command,planDigest;
        private Review(NativeImageTransform.Review value){image=value;trim=null;command="transformImage";planDigest=value.plan.identity;}
        private Review(NativeMediaTrim.Review value){image=null;trim=value;command="trimMedia";planDigest=value.identity;}
        public OwnerFilePreviewSource source(){return image==null?trim.source():image.source();}
        public JSONObject metadata()throws Exception{return image==null?trim.metadata():image.metadata();}
        public ProcessedOriginalFile execute(File privateCache,RunControl control)throws Exception{return image==null?NativeMediaTrim.execute(trim,privateCache,control):NativeImageTransform.execute(image,control);}
    }
    private NativeFileProcess(){}
    public static Review image(OwnerFilePreviewSource source,JSONObject options,RunControl control)throws Exception{return new Review(NativeImageTransform.review(source,options,control));}
    public static Review trim(OwnerFilePreviewSource source,long startMillis,long endMillis,File cache,RunControl control)throws Exception{return new Review(NativeMediaTrim.review(source,startMillis,endMillis,cache,control));}
}
