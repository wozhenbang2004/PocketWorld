package dev.pocketengine.features.attachments;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.NativeGroupOwnerWorkflows;
import dev.pocketengine.media.jobs.results.GroupMediaResults;
import dev.pocketengine.media.jobs.results.GroupResultAccess;
import org.json.JSONObject;

/** Native adapter only. The original window and actual group/audience host are re-entered for
 * each read; a result selection, UUID or cached bytes never turns into a private attachment. */
public final class GroupOriginalFilePreview {
    private GroupOriginalFilePreview(){}
    public static OwnerFilePreviewSource open(GroupMediaResults results,NativeGroupOwnerWorkflows owner,String window,GroupResultAccess.Selection selected,RunControl control)throws Exception {
        java.util.Objects.requireNonNull(results);java.util.Objects.requireNonNull(owner);final JSONObject[] original={null};
        owner.call(window,()->{JSONObject actual=results.inspect(selected,control);if(!actual.optBoolean("artifactReady"))throw new SecurityException("原群成果当前没有可读的实际文件");original[0]=actual;return actual;});
        JSONObject pinned=original[0].getJSONObject("artifact"),metadata=new JSONObject(pinned.toString()).put("sizeBytes",pinned.getLong("bytes")).put("name","群成果-"+selected.artifactId).put("groupSource",original[0]);
        return new OwnerFilePreviewSource("group_result",metadata,c->{
            byte[][] bytes={null};try{owner.call(window,()->{var opened=results.readArtifact(selected,c);same(pinned,opened.metadata().getJSONObject("artifact"));bytes[0]=opened.copyBytes();return new JSONObject();});return bytes[0];}catch(Exception failure){if(bytes[0]!=null)java.util.Arrays.fill(bytes[0],(byte)0);throw failure;}
        },c->owner.call(window,()->{JSONObject actual=results.inspect(selected,c);if(!actual.optBoolean("artifactReady"))throw new SecurityException("原群成果当前不可读");same(pinned,actual.getJSONObject("artifact"));return actual;}));
    }
    private static void same(JSONObject expected,JSONObject current)throws Exception {for(String key:new String[]{"artifactId","sha256","mimeType"})if(!expected.getString(key).equals(current.getString(key)))throw new SecurityException("原群成果版本或受众来源已变化");if(expected.getLong("bytes")!=current.getLong("bytes"))throw new SecurityException("原群成果大小已变化");}
}
