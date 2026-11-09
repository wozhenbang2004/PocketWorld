import test from 'node:test';
import assert from 'node:assert/strict';
import { continuousRectPath } from '../src/library/continuous-corners.ts';

test('all sizes use the same eight continuous corner segments', () => {
  for (const [width, height] of [[280,68],[320,110],[390,68],[430,190],[768,140],[110,38]]) {
    const path = continuousRectPath(width,height,30);
    assert.equal((path.match(/C/g)||[]).length,8); assert.match(path,/Z$/); assert.doesNotMatch(path,/NaN|Infinity/);
  }
});
test('degenerate or invalid measurements cannot make invalid SVG', () => {
  for (const dimensions of [[0,60,30],[-1,60,30],[100,NaN,30],[100,60,-1],[Infinity,60,30]])
    assert.equal(continuousRectPath(...dimensions as [number,number,number]),'');
  assert.equal(continuousRectPath(100,60,0),'M0 0H100V60H0Z');
});
test('oversized radius is clamped to the available half-height', () => {
  assert.equal(continuousRectPath(100,40,1000), continuousRectPath(100,40,20));
});
test('corners stay in bounds rather than globally stretching the shape', () => {
  const path = continuousRectPath(700,70,28);
  assert.match(path,/^M28 0H672/);
  const segments = [...path.matchAll(/C([\d.\s-]+)/g)].map(value=>value[1].trim().split(/\s+/).map(Number));
  for (const segment of segments) for (let index=0;index<segment.length;index+=2) {
    assert.ok(segment[index]>=0 && segment[index]<=700);
    assert.ok(segment[index+1]>=0 && segment[index+1]<=70);
  }
});
test('edge joins have zero curvature and the two halves share tangent and curvature', () => {
  const path=continuousRectPath(300,80,30);
  const [a,b]=[...path.matchAll(/C([\d.\s-]+)/g)].map(value=>value[1].trim().split(/\s+/).map(Number));
  type Point=[number,number];
  const first:Point[]=[[270,0],[a[0],a[1]],[a[2],a[3]],[a[4],a[5]]];
  const second:Point[]=[first[3],[b[0],b[1]],[b[2],b[3]],[b[4],b[5]]];
  const tangent=(p:Point[],end:boolean):Point=>end?[3*(p[3][0]-p[2][0]),3*(p[3][1]-p[2][1])]:[3*(p[1][0]-p[0][0]),3*(p[1][1]-p[0][1])];
  const curvature=(p:Point[],end:boolean)=>{const d=tangent(p,end),n=end?1:0;
    const dd=[6*(p[n+2][0]-2*p[n+1][0]+p[n][0]),6*(p[n+2][1]-2*p[n+1][1]+p[n][1])];
    return (d[0]*dd[1]-d[1]*dd[0])/Math.pow(Math.hypot(...d),3);};
  assert.equal(curvature(first,false),0); assert.equal(curvature(second,true),0);
  assert.ok(Math.abs(curvature(first,true)-curvature(second,false))<1e-6);
  const d1=tangent(first,true),d2=tangent(second,false);
  assert.ok(Math.hypot(d1[0]-d2[0],d1[1]-d2[1])<.0001);
});
test('cubic approximation remains within 0.03px of n=4 at a 30px radius', () => {
  const m=2**(-.25), b=2*m-1, a=.4341953546605872;
  for(let index=0;index<=1000;index++) {
    const t=index/1000,x=3*(1-t)**2*t*a+3*(1-t)*t*t*b+t*t*t*m,y=t*t*t*(1-m);
    const exact=1-(1-x**4)**.25; assert.ok(Math.abs(y-exact)*30<.03);
  }
});
