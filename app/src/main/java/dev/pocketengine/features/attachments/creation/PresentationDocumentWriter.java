package dev.pocketengine.features.attachments.creation;

import dev.pocketengine.core.RunControl;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real native PresentationML text objects and speaker-note parts. Static source editing is
 * available on the phone; rendering, charts, Morph and 3D are distinct unsupported capabilities. */
public final class PresentationDocumentWriter {
    private static final String P="http://schemas.openxmlformats.org/presentationml/2006/main",A="http://schemas.openxmlformats.org/drawingml/2006/main",R=OfficePackage.OFFICE_REL.substring(0,OfficePackage.OFFICE_REL.length()-1);
    private static final String GROUP="<p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>";
    private static final String MAP="<p:clrMap accent1=\"accent1\" accent2=\"accent2\" accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" bg1=\"lt1\" bg2=\"lt2\" folHlink=\"folHlink\" hlink=\"hlink\" tx1=\"dk1\" tx2=\"dk2\"/>";
    private PresentationDocumentWriter(){}
    public static byte[] write(DocumentDraft draft,RunControl control)throws Exception {
        var source=source(draft);Map<String,String> parts=new LinkedHashMap<>();StringBuilder types=new StringBuilder(),rels=new StringBuilder(),slides=new StringBuilder();
        int index=0;
        for(var slide:source.slides){
            control.checkpoint();int id=++index;slides.append("<p:sldId id=\"").append(255+id).append("\" r:id=\"rIdSlide").append(id).append("\"/>");rels.append(OfficePackage.relationship("rIdSlide"+id,"slide","slides/slide"+id+".xml"));
            String path="ppt/slides/slide"+id+".xml",notesPath="ppt/notesSlides/notesSlide"+id+".xml";
            types.append(OfficePackage.override(path,"presentationml.slide")).append(OfficePackage.override(notesPath,"presentationml.notesSlide"));
            StringBuilder shapes=new StringBuilder();shapes.append(shape(2,"title",List.of(slide.title()),0.55,0.35,12.2,0.9,32,true));
            if(!slide.paragraphs().isEmpty())shapes.append(shape(3,"body",slide.paragraphs(),0.65,1.5,12.0,5.4,22,false));
            int shapeId=4;for(var shape:slide.shapes())shapes.append(shape(shapeId++,shape.id(),List.of(shape.text()),shape.x(),shape.y(),shape.width(),shape.height(),20,false));
            parts.put(path,root("sld","<p:cSld name=\""+OfficePackage.xml(slide.id())+"\"><p:spTree>"+GROUP+shapes+"</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>"));
            parts.put("ppt/slides/_rels/slide"+id+".xml.rels",OfficePackage.relationships(OfficePackage.relationship("rIdLayout","slideLayout","../slideLayouts/slideLayout1.xml")+OfficePackage.relationship("rIdNotes","notesSlide","../notesSlides/notesSlide"+id+".xml")));
            String noteShape="<p:sp><p:nvSpPr><p:cNvPr id=\"2\" name=\"Notes\"/><p:cNvSpPr/><p:nvPr><p:ph type=\"body\" idx=\"1\"/></p:nvPr></p:nvSpPr><p:spPr/><p:txBody><a:bodyPr/><a:lstStyle/>"+paragraphs(Arrays.asList(slide.notes().split("\\n",-1)),18,false)+"</p:txBody></p:sp>";
            parts.put(notesPath,root("notes","<p:cSld><p:spTree>"+GROUP+noteShape+"</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>"));
            parts.put("ppt/notesSlides/_rels/notesSlide"+id+".xml.rels",OfficePackage.relationships(OfficePackage.relationship("rIdMaster","notesMaster","../notesMasters/notesMaster1.xml")+OfficePackage.relationship("rIdSlide","slide","../slides/slide"+id+".xml")));
        }
        types.append(OfficePackage.override("ppt/presentation.xml","presentationml.presentation.main")).append(OfficePackage.override("ppt/slideMasters/slideMaster1.xml","presentationml.slideMaster"))
                .append(OfficePackage.override("ppt/slideLayouts/slideLayout1.xml","presentationml.slideLayout")).append(OfficePackage.override("ppt/notesMasters/notesMaster1.xml","presentationml.notesMaster"))
                .append(OfficePackage.override("ppt/theme/theme1.xml","theme"));
        parts.put("[Content_Types].xml",OfficePackage.types(types.toString()));parts.put("_rels/.rels",OfficePackage.relationships(OfficePackage.relationship("rIdOffice","officeDocument","ppt/presentation.xml")));
        parts.put("ppt/presentation.xml",root("presentation","<p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"rIdMaster\"/></p:sldMasterIdLst><p:notesMasterIdLst><p:notesMasterId r:id=\"rIdNotesMaster\"/></p:notesMasterIdLst><p:sldIdLst>"+slides+"</p:sldIdLst><p:sldSz cx=\"12192000\" cy=\"6858000\" type=\"screen16x9\"/><p:notesSz cx=\"6858000\" cy=\"9144000\"/>"));
        parts.put("ppt/_rels/presentation.xml.rels",OfficePackage.relationships(rels+OfficePackage.relationship("rIdMaster","slideMaster","slideMasters/slideMaster1.xml")+OfficePackage.relationship("rIdNotesMaster","notesMaster","notesMasters/notesMaster1.xml")));
        parts.put("ppt/slideMasters/slideMaster1.xml",root("sldMaster","<p:cSld><p:spTree>"+GROUP+"</p:spTree></p:cSld>"+MAP+"<p:sldLayoutIdLst><p:sldLayoutId id=\"2147483649\" r:id=\"rIdLayout\"/></p:sldLayoutIdLst><p:txStyles><p:titleStyle/><p:bodyStyle/><p:otherStyle/></p:txStyles>"));
        parts.put("ppt/slideMasters/_rels/slideMaster1.xml.rels",OfficePackage.relationships(OfficePackage.relationship("rIdLayout","slideLayout","../slideLayouts/slideLayout1.xml")+OfficePackage.relationship("rIdTheme","theme","../theme/theme1.xml")));
        parts.put("ppt/slideLayouts/slideLayout1.xml",OfficePackage.XML+"<p:sldLayout xmlns:p=\""+P+"\" xmlns:a=\""+A+"\" xmlns:r=\""+R+"\" type=\"blank\" preserve=\"1\"><p:cSld name=\"Blank\"><p:spTree>"+GROUP+"</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>");
        parts.put("ppt/slideLayouts/_rels/slideLayout1.xml.rels",OfficePackage.relationships(OfficePackage.relationship("rIdMaster","slideMaster","../slideMasters/slideMaster1.xml")));
        parts.put("ppt/notesMasters/notesMaster1.xml",root("notesMaster","<p:cSld><p:spTree>"+GROUP+"</p:spTree></p:cSld>"+MAP+"<p:notesStyle/>"));
        parts.put("ppt/notesMasters/_rels/notesMaster1.xml.rels",OfficePackage.relationships(OfficePackage.relationship("rIdTheme","theme","../theme/theme1.xml")));
        parts.put("ppt/theme/theme1.xml",theme());byte[] bytes=OfficePackage.pack(parts,control);check(bytes,draft,control);return bytes;
    }
    public static JSONObject check(byte[] bytes,DocumentDraft draft,RunControl control)throws Exception {
        var source=source(draft);Map<Integer,StringBuilder> actual=new LinkedHashMap<>();for(var chunk:OfficePackage.projection(bytes,"pptx",source.slides.size(),control))actual.computeIfAbsent(chunk.unit,ignored->new StringBuilder()).append(chunk.text);
        int index=0,shapes=0;for(var slide:source.slides){control.checkpoint();int id=++index;StringBuilder expected=new StringBuilder(slide.title());for(String p:slide.paragraphs())expected.append(p);for(var shape:slide.shapes())expected.append(shape.text());
            if(!expected.toString().equals(actual.getOrDefault(id,new StringBuilder()).toString()))throw new SecurityException("实际演示页内容与固定结构源不同："+slide.id());
            if(!Arrays.asList(slide.notes().split("\\n",-1)).equals(OfficePackage.drawingParagraphs(bytes,"ppt/notesSlides/notesSlide"+id+".xml",control)))throw new SecurityException("实际讲稿与固定结构源不同："+slide.id());shapes+=1+(slide.paragraphs().isEmpty()?0:1)+slide.shapes().size();
        }
        return new JSONObject().put("format","pptx").put("structuralReadComplete",true).put("slideCount",source.slides.size()).put("declaredTextShapeCount",shapes).put("speakerNotesReadBack",true)
                .put("macroFree",true).put("externalRelationships",false).put("editableSourcePreserved",true).put("visualLayoutVerified",false).put("animationVerified",false).put("threeDimensionalVerified",false)
                .put("unsupportedFeatures",new JSONArray(new String[]{"charts","images","media","morph","3d","arbitrary_import_roundtrip"}));
    }
    private static StructuredOfficeDraft source(DocumentDraft draft){if(!draft.format.equals("pptx")||draft.office==null)throw new IllegalArgumentException("需要固定pptx结构源");return draft.office;}
    private static String root(String name,String body){return OfficePackage.XML+"<p:"+name+" xmlns:p=\""+P+"\" xmlns:a=\""+A+"\" xmlns:r=\""+R+"\">"+body+"</p:"+name+">";}
    private static String shape(int id,String name,List<String> values,double x,double y,double width,double height,int size,boolean bold){return "<p:sp><p:nvSpPr><p:cNvPr id=\""+id+"\" name=\""+OfficePackage.xml(name)+"\"/><p:cNvSpPr txBox=\"1\"/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x=\""+emu(x)+"\" y=\""+emu(y)+"\"/><a:ext cx=\""+emu(width)+"\" cy=\""+emu(height)+"\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:noFill/><a:ln><a:noFill/></a:ln></p:spPr><p:txBody><a:bodyPr wrap=\"square\"/><a:lstStyle/>"+paragraphs(values,size,bold)+"</p:txBody></p:sp>";}
    private static long emu(double inches){return Math.round(inches*914400);}
    private static String paragraphs(List<String> values,int size,boolean bold){StringBuilder xml=new StringBuilder();for(String value:values)xml.append("<a:p><a:r><a:rPr lang=\"zh-CN\" sz=\"").append(size*100).append("\" b=\"").append(bold?1:0).append("\"><a:solidFill><a:srgbClr val=\"202020\"/></a:solidFill></a:rPr><a:t xml:space=\"preserve\">").append(OfficePackage.xml(value)).append("</a:t></a:r><a:endParaRPr lang=\"zh-CN\"/></a:p>");return xml.toString();}
    private static String theme(){
        StringBuilder colors=new StringBuilder();String[] names={"dk1","lt1","dk2","lt2","accent1","accent2","accent3","accent4","accent5","accent6","hlink","folHlink"};String[] values={"000000","FFFFFF","202020","F2F2F2","4472C4","ED7D31","A5A5A5","FFC000","5B9BD5","70AD47","0563C1","954F72"};for(int i=0;i<names.length;i++)colors.append("<a:").append(names[i]).append("><a:srgbClr val=\"").append(values[i]).append("\"/></a:").append(names[i]).append('>');
        String fill="<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>",line="<a:ln w=\"12700\">"+fill+"<a:prstDash val=\"solid\"/></a:ln>",effect="<a:effectStyle><a:effectLst/></a:effectStyle>";
        return OfficePackage.XML+"<a:theme xmlns:a=\""+A+"\" name=\"PocketWorld\"><a:themeElements><a:clrScheme name=\"Office\">"+colors+"</a:clrScheme><a:fontScheme name=\"Office\"><a:majorFont><a:latin typeface=\"Calibri\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:majorFont><a:minorFont><a:latin typeface=\"Calibri\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:minorFont></a:fontScheme><a:fmtScheme name=\"Office\"><a:fillStyleLst>"+fill.repeat(3)+"</a:fillStyleLst><a:lnStyleLst>"+line.repeat(3)+"</a:lnStyleLst><a:effectStyleLst>"+effect.repeat(3)+"</a:effectStyleLst><a:bgFillStyleLst>"+fill.repeat(3)+"</a:bgFillStyleLst></a:fmtScheme></a:themeElements></a:theme>";
    }
}
