import cheerio from 'assets://js/lib/cheerio.min.js';
import CryptoJS from 'assets://js/lib/crypto-js.js';
import {title} from './helper.js';
let base;
let mode;
function movie() {
  return {vod_id:'script-film',vod_name:title, vod_play_from:'fixture',vod_play_url:'Sample$opaque-token'};
}
function page() {return {list:[movie()],page:1,pagecount:1,total:1};}
export default {
  async init(ext) {
    await Promise.resolve();
    base=ext.base;
    mode=ext.mode;
    if(cheerio.load('<b>test</b>')('b').text()!=='test')throw Error('cheerio failed');
    if(CryptoJS.MD5('test').toString()!=='098f6bcd4621d373cade4e832627b4f6')throw Error('crypto failed');
    local.set('fixture','loaded','yes');
  },
  async home(){return {...page(),class:[{type_id:'films',type_name:'Script movies'}]};},
  async homeVod(){return page();},
  async category(tid,pg){return page();},
  async detail(id){if(id!=='script-film')throw Error('Remote ID changed');return page();},
  async search(query,quick,pg){return page();},
  async play(flag,id){
    if(local.get('fixture','loaded','')!=='yes')throw Error('Missing local storage');
    if(id!=='opaque-token')throw Error('Remote token changed');
    return {parse:mode==='sniff'?1:0,url:mode==='proxy'?getProxyUrl()+'do=playlist':base+(mode==='sniff'?'/watch.html':'/protected/sample.m3u8'),header:{'X-TVBox-Test':'fixture'}};
  },
  async proxy(params){
    if(params.do!=='playlist')throw Error('Proxy query lost');
    const r=req(base+'/protected/sample.m3u8',{headers:{'X-TVBox-Test':'fixture'}});
    return [200,'application/vnd.apple.mpegurl',r.content.replace(/^(sample-\d+\.ts)$/gm,base+'/protected/$1')];
  }
};
