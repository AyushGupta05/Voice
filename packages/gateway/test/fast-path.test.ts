import {test} from 'node:test';import assert from 'node:assert/strict';
import {fastRoute} from '../src/fast-path.js';
const context={image:{name:'OS-2.ndpi'},viewer:{centerX:100,centerY:200,downsample:8}};
test('only whole, unambiguous display commands take the instant route',()=>{
 assert.deepEqual(fastRoute('Zoom in a little.',context),{tool:'qupath_focus',arguments:{x:100,y:200,downsample:4},reply:'Zooming in.'});
 assert.deepEqual(fastRoute('Okay Luma, zoom out a lot please',context)?.arguments,{x:100,y:200,downsample:32});
 assert.deepEqual(fastRoute('Hide the cells.',context),{tool:'qupath_overlays',arguments:{detections:false},reply:'Hiding cells.'});
 assert.deepEqual(fastRoute('Fill cells',context)?.arguments,{detections:true,fill:true});
 for(const other of ['Zoom in on the positive cells','Show me the detected cells in this region','Delete the cells','zoom in and count them','Hide the positive cells'])assert.equal(fastRoute(other,context),undefined);
 assert.equal(fastRoute('Zoom in',{viewer:{centerX:1,centerY:1,downsample:1}}),undefined);
 assert.equal(fastRoute('Zoom in',{image:{},viewer:{centerX:1,centerY:1,downsample:0.05}}),undefined);
});
