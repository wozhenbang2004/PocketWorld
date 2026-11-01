package dev.pocketengine.features.workspace;

import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Native-only continuation of one exact immutable file, against a separately observed current
 * target head. Historical bytes are a new draft, never silently replaced with latest bytes. */
public final class WorkspaceArtifactContinuation {
    private final VersionedWorkspace workspace;private final WorkspaceArtifacts artifacts;
    private final WorkspaceArtifacts.ArtifactRef artifact;private final byte[] source;
    public final VersionedWorkspace.FileSnapshot expected;
    WorkspaceArtifactContinuation(VersionedWorkspace workspace,WorkspaceArtifacts artifacts,String id,String sha)throws Exception{
        this.workspace=workspace;this.artifacts=artifacts;artifact=artifacts.reference(id,sha);source=artifacts.bytes(artifact);
        expected=workspace.snapshot(artifact.projectId,artifact.path,0);
        if(!expected.projectId.equals(artifact.projectId)||!expected.path.equals(artifact.path))throw new SecurityException("固定成果与原文件目标不同");
    }
    public JSONObject source()throws Exception{return artifact.json();}
    public byte[] originalBytes()throws Exception{requireSource();return source.clone();}
    public String originalText()throws Exception{return text(originalBytes());}
    public String currentText()throws Exception{return expected.tombstone?"":text(expected.bytes());}
    public VersionedWorkspace.FileSnapshot saveBytes(byte[] edited)throws Exception{
        requireSource();return workspace.writeFromArtifact(expected,edited,artifact);
    }
    public VersionedWorkspace.FileSnapshot saveText(String edited)throws Exception{
        if(edited==null||edited.length()>65536)throw new IllegalArgumentException("此文字编辑入口最多65536字符，原稿未保存或截断");
        java.nio.ByteBuffer encoded=StandardCharsets.UTF_8.newEncoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(edited));byte[] bytes=new byte[encoded.remaining()];encoded.get(bytes);return saveBytes(bytes);
    }
    private void requireSource()throws Exception{byte[] current=artifacts.bytes(artifact);if(!java.util.Arrays.equals(source,current))throw new SecurityException("所选历史成果实际字节已变化；未换用最新版本");}
    private static String text(byte[] bytes)throws Exception{String value=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();if(value.indexOf('\0')>=0||value.length()>65536)throw new IllegalArgumentException("所选原件不能在此文字入口完整编辑；请保留二进制或使用原格式工具");return value;}
}
