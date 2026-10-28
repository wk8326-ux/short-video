import {load} from 'cheerio/slim';
import {JSONPath} from 'jsonpath-plus';
export default {
  load,
  jp:(path,json)=>JSONPath({path,json}),
  pdfh:(html,selector)=>globalThis.pdfh(html,selector),
  pdfa:(html,selector)=>globalThis.pdfa(html,selector),
  pd:(html,selector,base)=>globalThis.pd(html,selector,base),
  jinja2:(text,values)=>globalThis.jinja ? globalThis.jinja.render(text,values) : text
};
