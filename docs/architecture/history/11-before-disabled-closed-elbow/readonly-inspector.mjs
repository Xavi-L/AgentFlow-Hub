import fs from 'node:fs';
import inspector from 'node:inspector';
const compiler='/Users/xavier/AgentFlow-Hub/.agents/skills/archify/renderers/workflow/workflow-compiler.mjs';
const input='/Users/xavier/AgentFlow-Hub/docs/architecture/11-restart-recovery.candidate.workflow.json';
const output='/Users/xavier/AgentFlow-Hub/docs/architecture/11-restart-recovery.readonly-predicate-diagnostic.json';
const session=new inspector.Session();session.connect();
const post=(name,args={})=>new Promise((res,rej)=>session.post(name,args,(e,r)=>e?rej(e):res(r)));
await post('Debugger.enable');
const lines=fs.readFileSync(compiler,'utf8').split('\n');
const line=lines.findIndex(x=>x.startsWith('function readableCandidateIsFeasible('))+1;
const captured=[];
session.on('Debugger.paused',({params})=>{
 const expression=`JSON.stringify({edge:edge.id,points,fromSide,toSide,label:candidateLabelRect(edge,points),nodes:[...nodes.values()],predicates:{orthogonalRoute:orthogonalRoute(points),routeHonorsEndpointSides:routeHonorsEndpointSides(points,fromSide,toSide),routeMeetsHardRhythm:routeMeetsHardRhythm(points),routeClearsEndpointNodes:routeClearsEndpointNodes(points,from,to),routeClearsUnrelatedNodes:routeClearsUnrelatedNodes(edge,points),routeLabelClearsNodes:routeLabelClearsNodes(edge,points),routeClearsPlacedLabels:routeClearsPlacedLabels(edge,points),routeClearsLegend:routeClearsLegend(edge,points),routeClearsSceneLabelObstacles:routeClearsSceneLabelObstacles(edge,points),routeClearsFrameBorders:routeClearsFrameBorders(points),routeFitsCanvasOrigin:routeFitsCanvasOrigin(edge,points)},priorRoutes:[...pathCache.entries()].map(([e,r])=>({edge:e.id,points:r.points,label:labelRectFor(e,workflow.edges.indexOf(e))}))})`;
 session.post('Debugger.evaluateOnCallFrame',{callFrameId:params.callFrames[0].callFrameId,expression,returnByValue:true},(err,r)=>{captured.push(err?{error:String(err)}:r.exceptionDetails?{exception:r.exceptionDetails}:JSON.parse(r.result.value));session.post('Debugger.removeBreakpoint',{breakpointId:bp.breakpointId});session.post('Debugger.resume');});
});
const bp=await post('Debugger.setBreakpointByUrl',{url:'file://'+compiler,lineNumber:line,condition:"edge.id === 'rr-scan-fail' && edge.route === 'straight' && points.length === 2"});
let failure;
try{const {compileWorkflow}=await import('file://'+compiler);compileWorkflow({workflow:JSON.parse(fs.readFileSync(input)),qualityProfile:'showcase'});}catch(e){failure=e.stack;}
session.disconnect();
fs.writeFileSync(output,JSON.stringify({evidenceKind:'read-only-inspector-predicate-observation-not-cli-acceptance',installedSkillModified:false,input,captured,failure},null,2)+'\n');
console.log(JSON.stringify(captured.map(x=>({edge:x.edge,points:x.points,label:x.label,predicates:x.predicates})),null,2));
