package dev.pocketengine.features.local.project;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.util.ArrayList;
import java.util.List;

/** Bounded streaming matcher only. Its caller must own the original selected-file/SHA gate;
 * neither bytes nor an offset grant file access or authorize a write. */
final class ProjectHexPattern {
    static final int MAX_PATTERN_BYTES=120,MAX_SCAN_BYTES=262144;
    private ProjectHexPattern() {}
    static byte[] parse(String text) {
        if(text==null||text.isEmpty()||text.length()>MAX_PATTERN_BYTES*2||(text.length()&1)!=0)throw new IllegalArgumentException("hex模式需1至120字节的连续十六进制字串，不接受通配/空格/0x前缀");
        byte[] result=new byte[text.length()/2];for(int i=0;i<result.length;i++){int high=Character.digit(text.charAt(i*2),16),low=Character.digit(text.charAt(i*2+1),16);if(high<0||low<0||text.charAt(i*2)>127||text.charAt(i*2+1)>127)throw new IllegalArgumentException("hex模式只接受ASCII十六进制数位");result[i]=(byte)((high<<4)|low);}return result;
    }
    static final class Page {
        final List<Long> offsets;final long scannedBytes,nextOffset;final boolean complete;
        private Page(List<Long> offsets,long scanned,long next,boolean complete){this.offsets=List.copyOf(offsets);scannedBytes=scanned;nextOffset=next;this.complete=complete;}
    }
    static Page find(SeekableByteChannel channel,long expectedBytes,long start,byte[] supplied,int limit,ProjectFileQueries.Current current)throws Exception {
        if(expectedBytes<0||expectedBytes>ProjectSources.MAX_FILE_BYTES||start<0||start>expectedBytes||supplied==null||supplied.length<1||supplied.length>MAX_PATTERN_BYTES||limit<1||limit>ProjectFileQueries.MAX_RESULTS)throw new IllegalArgumentException("原hex搜索范围、模式或页长无效");
        byte[] pattern=supplied.clone();int[] prefix=new int[pattern.length];for(int i=1,k=0;i<pattern.length;i++){while(k>0&&pattern[i]!=pattern[k])k=prefix[k-1];if(pattern[i]==pattern[k])k++;prefix[i]=k;}
        current.check();channel.position(start);long position=start,until=Math.min(expectedBytes,start+MAX_SCAN_BYTES);int matched=0,zeroReads=0;List<Long> offsets=new ArrayList<>();ByteBuffer buffer=ByteBuffer.allocate(4096);
        while(position<until){current.check();buffer.clear();buffer.limit((int)Math.min(buffer.capacity(),until-position));int count=channel.read(buffer);if(count<0)throw new EOFException("原文件在hex搜索的准确范围前结束");if(count==0){if(++zeroReads>16)throw new IOException("原hex文件读取未取得进度，不返回假完成页");continue;}zeroReads=0;buffer.flip();
            while(buffer.hasRemaining()){if(((position-start)&4095)==0)current.check();byte value=buffer.get();position++;while(matched>0&&value!=pattern[matched])matched=prefix[matched-1];if(value==pattern[matched])matched++;
                if(matched==pattern.length){long at=position-pattern.length;offsets.add(at);matched=prefix[matched-1];if(offsets.size()==limit){current.check();boolean complete=position==expectedBytes;return new Page(offsets,position-start,complete?-1:at+1,complete);}}
            }
        }
        current.check();boolean complete=position==expectedBytes;long next=complete?-1:position-matched;if(!complete&&next<=start)throw new IllegalStateException("原hex扫描没有推进，不签发重复cursor");return new Page(offsets,position-start,next,complete);
    }
}
