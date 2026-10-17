import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';
import assert from 'node:assert/strict';
import { resolve } from 'node:path';
const root=process.cwd();
const transport=new StdioClientTransport({command:process.execPath,args:['--import','tsx',resolve(root,'packages/gateway/src/mcp.ts')],env:{...process.env as Record<string,string>,LUMA_ROOT:root},stderr:'pipe'});
const client=new Client({name:'luma-integration-smoke',version:'0.1.0'});
try {
 await client.connect(transport);
 const inventory=await client.listTools();assert.equal(inventory.tools.length,23);
 const read=async(name:string,args={})=>{
  const response=await client.callTool({name,arguments:args});assert(!response.isError,JSON.stringify(response));
  const content=response.content as Array<{type:string;text?:string}>;
  return JSON.parse(content.find(item=>item.type==='text')!.text!);
 };
 const context=await read('qupath_context');assert.equal(context.application,'QuPath');
 const commands=await read('qupath_commands',{query:'measurement'});assert(commands.length>0);
 const knowledge=await read('qupath_knowledge',{query:'cell detection'});assert(knowledge.documentCount>=4);assert(knowledge.results.length>0);
 const resources=await client.listResources();assert(resources.resources.some(resource=>resource.uri==='qupath://guide'));
 const guide=await client.readResource({uri:'qupath://guide'});assert(guide.contents.length===1);
 console.log(JSON.stringify({mcp:'passed',tools:inventory.tools.length,image:context.image,menuMatches:commands.length,knowledgeDocuments:knowledge.documentCount,resources:resources.resources.length},null,2));
}finally{await client.close();}
