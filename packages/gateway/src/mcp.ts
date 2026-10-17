import { Server } from '@modelcontextprotocol/sdk/server/index.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import { CallToolRequestSchema, ListToolsRequestSchema, ListResourcesRequestSchema, ReadResourceRequestSchema, ListPromptsRequestSchema, GetPromptRequestSchema } from '@modelcontextprotocol/sdk/types.js';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { tools, validateTool } from './tools.js';
const root=resolve(process.env.LUMA_ROOT??process.cwd());
const server=new Server({name:'luma-qupath',version:'0.1.0'},{capabilities:{tools:{},resources:{},prompts:{}}});
server.setRequestHandler(ListToolsRequestSchema,async()=>({tools:tools.map(({name,description,inputSchema,risk})=>({name,description,inputSchema:inputSchema as {type:'object'},annotations:{readOnlyHint:risk==='read',destructiveHint:risk==='data'||risk==='code',idempotentHint:risk==='read',openWorldHint:risk==='code'}}))}));
server.setRequestHandler(CallToolRequestSchema,async request=>{
  try {
    const args=request.params.arguments??{};validateTool(request.params.name,args);
    const token=(await readFile(resolve(root,'.local/state/gateway-token'),'utf8')).trim();
    const response=await fetch('http://127.0.0.1:49555/tools/call',{method:'POST',headers:{Authorization:`Bearer ${token}`,'Content-Type':'application/json'},body:JSON.stringify({name:request.params.name,arguments:args}),signal:AbortSignal.timeout(130000)});
    const value=await response.json() as {result?:unknown;error?:string};if(!response.ok)throw new Error(value.error??'Gateway request failed.');
    const result=value.result;
    if(request.params.name==='qupath_snapshot'&&result&&typeof result==='object'&&'data' in result) return {content:[{type:'image',data:String(result.data),mimeType:'image/png'}]};
    return {content:[{type:'text',text:JSON.stringify(result)}]};
  }catch(error){return {isError:true,content:[{type:'text',text:error instanceof Error?error.message:String(error)}]};}
});
server.setRequestHandler(ListResourcesRequestSchema,async()=>({resources:[{uri:'qupath://guide',name:'QuPath agent operating guide',mimeType:'text/markdown'}]}));
server.setRequestHandler(ReadResourceRequestSchema,async request=>{
  if(request.params.uri!=='qupath://guide')throw new Error('Unknown resource');
  return {contents:[{uri:request.params.uri,mimeType:'text/markdown',text:await readFile(resolve(root,'skills/qupath/SKILL.md'),'utf8')}]};
});
server.setRequestHandler(ListPromptsRequestSchema,async()=>({prompts:[{name:'qupath-researcher',description:'Ground an agent in the current QuPath session and approval rules.'}]}));
server.setRequestHandler(GetPromptRequestSchema,async request=>{
  if(request.params.name!=='qupath-researcher')throw new Error('Unknown prompt');
  return {messages:[{role:'user',content:{type:'text',text:await readFile(resolve(root,'skills/qupath/SKILL.md'),'utf8')}}]};
});
await server.connect(new StdioServerTransport());
