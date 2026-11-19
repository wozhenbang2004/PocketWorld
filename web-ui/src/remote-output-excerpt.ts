type Row=Record<string,unknown>;
/** UTF-16 positions are indices into one native string, not bytes or offsets into a live log. */
export function receivedOutputParts(result:Row,source:Row):{tail:string;omitted:number}|null{
  const part=result.outputExcerpt;if(!part||typeof part!=='object'||Array.isArray(part))return null;const p=part as Row;
  if(p.protocol!=='original_received_output_parts_v1'||result.outputViewTruncated!==true||
    !['jobId','jobOperationId','environmentId','worker','workspaceIdentity'].every(k=>typeof source[k]==='string'&&source[k]&&source[k]===p[k]&&p[k]===result[k==='jobOperationId'?'operationId':k]))return null;
  if(typeof p.tail!=='string'||!p.tail.length||typeof result.output!=='string'||
    typeof p.tailStart!=='number'||!Number.isSafeInteger(p.tailStart)||typeof p.receivedUnits!=='number'||!Number.isSafeInteger(p.receivedUnits)||
    p.headEnd!==result.output.length||result.output.length>49152||p.tail.length>16384||p.tailStart<=p.headEnd||p.tailStart+p.tail.length!==p.receivedUnits)return null;
  return {tail:p.tail,omitted:p.tailStart-p.headEnd};
}
