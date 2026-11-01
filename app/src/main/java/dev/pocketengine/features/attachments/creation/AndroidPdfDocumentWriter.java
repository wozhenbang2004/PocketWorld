package dev.pocketengine.features.attachments.creation;

import android.graphics.*;
import android.graphics.pdf.*;
import android.os.ParcelFileDescriptor;
import android.text.*;
import dev.pocketengine.core.RunControl;
import java.io.*;
import java.nio.file.Files;
import org.json.JSONObject;

/** Platform PdfDocument, StaticLayout and PdfRenderer. Pagination is measured before drawing;
 * content is never clipped to manufacture a successful one-page PDF. No web/network renderer. */
public final class AndroidPdfDocumentWriter {
    public static final class Result {public final byte[] bytes;public final JSONObject check;private Result(byte[] bytes,JSONObject check){this.bytes=bytes;this.check=check;}}
    private static final int WIDTH=595,HEIGHT=842,MARGIN=48,CONTENT=WIDTH-2*MARGIN;
    private AndroidPdfDocumentWriter(){}
    public static Result write(DocumentDraft draft,File dedicatedCache,RunControl control)throws Exception{
        control.check();PdfDocument pdf=new PdfDocument();PdfDocument.Page page=null;int pages=0,y=MARGIN;ByteArrayOutputStream output=new ByteArrayOutputStream();
        try{
            for(var block:draft.blocks){control.check();TextPaint text=new TextPaint(Paint.ANTI_ALIAS_FLAG);text.setColor(Color.BLACK);text.setTextSize(block.heading==1?draft.fontPoints+6:block.heading==2?draft.fontPoints+3:block.heading==3?draft.fontPoints+1:draft.fontPoints);text.setTypeface(block.heading>0?Typeface.DEFAULT_BOLD:Typeface.DEFAULT);
                if(block.table()){
                    int columns=block.cells.size(),cellWidth=CONTENT/columns,maximum=0;StaticLayout[] values=new StaticLayout[columns];for(int i=0;i<columns;i++){values[i]=layout(block.cells.get(i),text,cellWidth-10);maximum=Math.max(maximum,values[i].getHeight());}int rowHeight=maximum+12;
                    if(rowHeight>HEIGHT-2*MARGIN)throw new IllegalArgumentException("单个表格行超过整页，请在可编辑源拆分；未裁掉文字");
                    if(page==null||y+rowHeight>HEIGHT-MARGIN){if(page!=null)pdf.finishPage(page);if(++pages>100)throw new IllegalArgumentException("PDF超过100页，未发布部分文档");page=pdf.startPage(new PdfDocument.PageInfo.Builder(WIDTH,HEIGHT,pages).create());y=MARGIN;}
                    Paint border=new Paint(Paint.ANTI_ALIAS_FLAG);border.setStyle(Paint.Style.STROKE);border.setStrokeWidth(.5f);border.setColor(Color.DKGRAY);Canvas canvas=page.getCanvas();for(int i=0;i<columns;i++){int left=MARGIN+i*cellWidth;canvas.drawRect(left,y,left+cellWidth,y+rowHeight,border);canvas.save();canvas.translate(left+5,y+6);values[i].draw(canvas);canvas.restore();}y+=rowHeight+3;
                }else{
                    StaticLayout paragraph=layout(block.text.isEmpty()?" ":block.text,text,CONTENT);int line=0;
                    while(line<paragraph.getLineCount()){control.check();int height=paragraph.getLineBottom(line)-paragraph.getLineTop(line);if(page==null||y+height>HEIGHT-MARGIN){if(page!=null)pdf.finishPage(page);if(++pages>100)throw new IllegalArgumentException("PDF超过100页，未发布部分文档");page=pdf.startPage(new PdfDocument.PageInfo.Builder(WIDTH,HEIGHT,pages).create());y=MARGIN;}
                        int top=paragraph.getLineTop(line);Canvas canvas=page.getCanvas();canvas.save();canvas.clipRect(MARGIN,y,WIDTH-MARGIN,y+height);canvas.translate(MARGIN,y-top);paragraph.draw(canvas);canvas.restore();y+=height;line++;}y+=6;
                }
            }
            if(page!=null){pdf.finishPage(page);page=null;}control.check();
            pdf.writeTo(new FilterOutputStream(output){private int size;@Override public void write(int v)throws IOException{write(new byte[]{(byte)v},0,1);}@Override public void write(byte[] b,int off,int count)throws IOException{if((size+=count)>2*1024*1024)throw new IOException("PDF超过2MiB，未保存部分文档");try{control.check();}catch(Exception stop){throw new InterruptedIOException("原PDF生成已取消");}out.write(b,off,count);}});
        }finally{if(page!=null)try{pdf.finishPage(page);}catch(Exception ignored){}pdf.close();}
        byte[] bytes=output.toByteArray();if(bytes.length<8||bytes[0]!='%'||bytes[1]!='P'||bytes[2]!='D'||bytes[3]!='F')throw new SecurityException("平台未返回真实PDF字节");
        if(Files.isSymbolicLink(dedicatedCache.toPath())||!dedicatedCache.isDirectory()&&!dedicatedCache.mkdirs())throw new IOException("无法准备PDF原件检查缓存");File file=File.createTempFile("document-check-",".pdf",dedicatedCache);
        try{Files.write(file.toPath(),bytes);try(ParcelFileDescriptor descriptor=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);PdfRenderer renderer=new PdfRenderer(descriptor)){if(renderer.getPageCount()!=pages||pages<1)throw new SecurityException("PDF实际页数与分页结果不符");for(int i=0;i<pages;i++){control.check();try(PdfRenderer.Page actual=renderer.openPage(i)){if(actual.getWidth()!=WIDTH||actual.getHeight()!=HEIGHT)throw new SecurityException("PDF实际页面尺寸与生成方案不同");}}}}
        finally{Files.deleteIfExists(file.toPath());}
        control.check();return new Result(bytes,new JSONObject().put("format","pdf").put("pageCount",pages).put("pagesReopened",true).put("pageWidthPoints",WIDTH).put("pageHeightPoints",HEIGHT).put("marginsPoints",MARGIN).put("measuredPagination",true).put("visualLayoutVerified",false).put("editableSourcePreserved",true));
    }
    private static StaticLayout layout(String value,TextPaint paint,int width){return StaticLayout.Builder.obtain(value,0,value.length(),paint,width).setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY).setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL).build();}
}
