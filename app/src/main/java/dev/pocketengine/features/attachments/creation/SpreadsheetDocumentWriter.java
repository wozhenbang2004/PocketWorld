package dev.pocketengine.features.attachments.creation;

import dev.pocketengine.core.RunControl;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real editable SpreadsheetML, recalculated from the fixed native source on each generation.
 * Formula coverage and missing caches are explicit; this never claims to run Excel. */
public final class SpreadsheetDocumentWriter {
    private static final String NS="http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private SpreadsheetDocumentWriter(){}
    public static byte[] write(DocumentDraft draft,RunControl control)throws Exception {
        StructuredOfficeDraft source=source(draft);var evaluator=new OfficeFormulaEvaluator(source,control);evaluator.report(source);
        Map<String,String> parts=new LinkedHashMap<>();StringBuilder types=new StringBuilder(),book=new StringBuilder(OfficePackage.XML)
                .append("<workbook xmlns=\"").append(NS).append("\" xmlns:r=\"").append(OfficePackage.OFFICE_REL.substring(0,OfficePackage.OFFICE_REL.length()-1)).append("\"><bookViews><workbookView/></bookViews><sheets>");
        StringBuilder relations=new StringBuilder();int index=0;
        for(var sheet:source.sheets) {
            control.checkpoint();int id=++index;String path="xl/worksheets/sheet"+id+".xml";
            types.append(OfficePackage.override(path,"spreadsheetml.worksheet"));book.append("<sheet name=\"").append(OfficePackage.xml(sheet.name())).append("\" sheetId=\"").append(id).append("\" r:id=\"rId").append(id).append("\"/>");
            relations.append(OfficePackage.relationship("rId"+id,"worksheet","worksheets/sheet"+id+".xml"));
            StringBuilder xml=new StringBuilder(OfficePackage.XML).append("<worksheet xmlns=\"").append(NS).append("\"><sheetData>");int row=0;
            for(var cell:sheet.cells()) {
                control.checkpoint();int at=StructuredOfficeDraft.row(cell.ref());if(at!=row){if(row>0)xml.append("</row>");row=at;xml.append("<row r=\"").append(row).append("\">");}
                xml.append("<c r=\"").append(cell.ref()).append("\" s=\"").append(style(cell.numberFormat())).append('"');
                if(cell.formula()!=null){var value=evaluator.value(sheet.name(),cell);
                    if(value.current())xml.append("><f>").append(OfficePackage.xml(cell.formula())).append("</f><v>").append(number(value.number())).append("</v></c>");
                    // A missing cache never disables an Office formula. Unresolved expressions,
                    // including unknown functions and their dependents, are explicit string cells.
                    // The complete original expression remains in the fixed editable source.
                    else xml.append(" t=\"inlineStr\"><is><t xml:space=\"preserve\">").append(OfficePackage.xml(literalFormula(cell.formula()))).append("</t></is></c>");
                }
                else if(cell.value() instanceof String text)xml.append(" t=\"inlineStr\"><is><t xml:space=\"preserve\">").append(OfficePackage.xml(text)).append("</t></is></c>");
                else if(cell.value() instanceof Boolean value)xml.append(" t=\"b\"><v>").append(value?1:0).append("</v></c>");
                else xml.append("><v>").append(number(StructuredOfficeDraft.decimal((Number)cell.value()))).append("</v></c>");
            }
            if(row>0)xml.append("</row>");xml.append("</sheetData></worksheet>");parts.put(path,xml.toString());
        }
        book.append("</sheets><calcPr calcId=\"0\" calcMode=\"auto\" fullCalcOnLoad=\"0\"/></workbook>");
        types.append(OfficePackage.override("xl/workbook.xml","spreadsheetml.sheet.main")).append(OfficePackage.override("xl/styles.xml","spreadsheetml.styles"));
        parts.put("[Content_Types].xml",OfficePackage.types(types.toString()));parts.put("_rels/.rels",OfficePackage.relationships(OfficePackage.relationship("rIdOffice","officeDocument","xl/workbook.xml")));
        parts.put("xl/workbook.xml",book.toString());parts.put("xl/_rels/workbook.xml.rels",OfficePackage.relationships(relations+OfficePackage.relationship("rIdStyles","styles","styles.xml")));
        parts.put("xl/styles.xml",styles());byte[] bytes=OfficePackage.pack(parts,control);check(bytes,draft,control);return bytes;
    }
    public static JSONObject check(byte[] bytes,DocumentDraft draft,RunControl control)throws Exception {
        var source=source(draft);var evaluator=new OfficeFormulaEvaluator(source,control);JSONObject calc=evaluator.report(source);Map<String,StringBuilder> actual=new LinkedHashMap<>();
        for(var chunk:OfficePackage.projection(bytes,"xlsx",source.sheets.size(),control))actual.computeIfAbsent(chunk.locator.get("sheet")+"!"+chunk.locator.get("cell"),ignored->new StringBuilder()).append(chunk.text);
        int count=0,blank=0;
        for(var sheet:source.sheets)for(var cell:sheet.cells()){
            control.checkpoint();count++;String expected;
            if(cell.formula()!=null){var value=evaluator.value(sheet.name(),cell);expected=value.current()?number(value.number()):literalFormula(cell.formula());}
            else expected=cell.value() instanceof Number n?number(StructuredOfficeDraft.decimal(n)):cell.value().toString();
            String key=sheet.name()+"!"+cell.ref();if(expected.isEmpty())blank++;if(!expected.equals(actual.getOrDefault(key,new StringBuilder()).toString()))throw new SecurityException("实际工作簿的单元格内容与固定结构源不一致："+key);actual.remove(key);
        }
        if(!actual.isEmpty())throw new SecurityException("实际工作簿存在结构源未声明的内容");
        return new JSONObject().put("format","xlsx").put("structuralReadComplete",true).put("sheetCount",source.sheets.size()).put("declaredCellCount",count).put("blankCellsNotProjected",blank)
                .put("calculation",calc).put("unresolvedFormulaOutput","literal_text").put("sourceRevisionRequiredForRecalculation",calc.getInt("literalFormulaCount")>0)
                .put("macroFree",true).put("externalRelationships",false).put("externalFormulasWritten",false).put("editableSourcePreserved",true).put("visualLayoutVerified",false)
                .put("unsupportedFeatures",new JSONArray(new String[]{"charts","named_ranges","external_links","iterative_calculation","arbitrary_import_roundtrip"}));
    }
    private static StructuredOfficeDraft source(DocumentDraft draft){if(!draft.format.equals("xlsx")||draft.office==null)throw new IllegalArgumentException("需要固定xlsx结构源");return draft.office;}
    private static String literalFormula(String formula){return "未计算公式：="+formula;}
    private static String number(java.math.BigDecimal number){return number.signum()==0?"0":number.stripTrailingZeros().toPlainString();}
    private static int style(String format){return switch(format){case "integer"->1;case "decimal"->2;case "percent"->3;default->0;};}
    private static String styles(){return OfficePackage.XML+"<styleSheet xmlns=\""+NS+"\"><fonts count=\"1\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts><fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills><borders count=\"1\"><border/></borders><cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs><cellXfs count=\"4\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/><xf numFmtId=\"1\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/><xf numFmtId=\"2\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/><xf numFmtId=\"10\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/></cellXfs><cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>";}
}
