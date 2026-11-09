const http = require('http');
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const PORT = 3000;
const APK_CANDIDATES = [
  path.join(__dirname, 'app/build/outputs/apk/debug/app-debug.apk'),
  path.join(__dirname, '.build-outputs/app-debug.apk')
];

function getApkPath() {
  return APK_CANDIDATES.find(p => fs.existsSync(p)) || APK_CANDIDATES[0];
}

// --- Built-in ZIP & PPTX/DOCX/XLSX Parser using Node's native zlib ---
function readZipBuffer(buf) {
  const entries = {};
  let eocdOffset = -1;
  for (let i = buf.length - 22; i >= Math.max(0, buf.length - 65558); i--) {
    if (buf.readUInt32LE(i) === 0x06054b50) {
      eocdOffset = i;
      break;
    }
  }
  if (eocdOffset === -1) return entries;

  const totalEntries = buf.readUInt16LE(eocdOffset + 10);
  const cdOffset = buf.readUInt32LE(eocdOffset + 16);
  let ptr = cdOffset;

  for (let i = 0; i < totalEntries && ptr + 46 <= buf.length; i++) {
    if (buf.readUInt32LE(ptr) !== 0x02014b50) break;
    const method = buf.readUInt16LE(ptr + 10);
    const compSize = buf.readUInt32LE(ptr + 20);
    const nameLen = buf.readUInt16LE(ptr + 28);
    const extraLen = buf.readUInt16LE(ptr + 30);
    const commentLen = buf.readUInt16LE(ptr + 32);
    const localHeaderOffset = buf.readUInt32LE(ptr + 42);
    const name = buf.slice(ptr + 46, ptr + 46 + nameLen).toString('utf8');
    ptr += 46 + nameLen + extraLen + commentLen;

    if (localHeaderOffset + 30 > buf.length) continue;
    if (buf.readUInt32LE(localHeaderOffset) !== 0x04034b50) continue;
    const lNameLen = buf.readUInt16LE(localHeaderOffset + 26);
    const lExtraLen = buf.readUInt16LE(localHeaderOffset + 28);
    const dataStart = localHeaderOffset + 30 + lNameLen + lExtraLen;
    if (dataStart + compSize > buf.length) continue;

    const rawSlice = buf.slice(dataStart, dataStart + compSize);
    try {
      if (method === 0) {
        entries[name] = rawSlice;
      } else if (method === 8) {
        entries[name] = zlib.inflateRawSync(rawSlice);
      }
    } catch (_) {}
  }
  return entries;
}

function getAttr(tagStr, attrName) {
  const re = new RegExp('(?:^|\\s|[a-zA-Z0-9_]+:)' + attrName + '=\\s*["\']([^"\']*)["\']', 'i');
  const m = tagStr.match(re);
  return m ? m[1] : null;
}

function decodeXmlEntities(str) {
  return (str || '')
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&#(\d+);/g, (_, d) => String.fromCharCode(parseInt(d, 10)))
    .replace(/&#x([0-9a-fA-F]+);/g, (_, h) => String.fromCharCode(parseInt(h, 16)));
}

function resolveRelPath(basePartPath, target) {
  if (!target) return '';
  const cleanTarget = target.split('#')[0].replace(/\\/g, '/');
  if (cleanTarget.startsWith('/')) return cleanTarget.replace(/^\/+/, '');
  const baseParts = basePartPath.split('/');
  baseParts.pop();
  for (const seg of cleanTarget.split('/')) {
    if (!seg || seg === '.') continue;
    if (seg === '..') baseParts.pop();
    else baseParts.push(seg);
  }
  return baseParts.join('/');
}

function parseRelsXml(xmlStr, sourcePath) {
  const rels = {};
  if (!xmlStr) return rels;
  const re = /<(?:\w+:)?Relationship\b([^>]*)\/?>/g;
  let m;
  while ((m = re.exec(xmlStr)) !== null) {
    const id = getAttr(m[1], 'Id');
    const type = getAttr(m[1], 'Type') || '';
    const target = getAttr(m[1], 'Target') || '';
    const mode = getAttr(m[1], 'TargetMode') || '';
    if (id && target && mode.toLowerCase() !== 'external') {
      rels[id] = { id, type, target: resolveRelPath(sourcePath, target) };
    }
  }
  return rels;
}

function rgbToHsl(r, g, b) {
  r /= 255; g /= 255; b /= 255;
  const max = Math.max(r, g, b), min = Math.min(r, g, b);
  let h = 0, s = 0, l = (max + min) / 2;
  if (max !== min) {
    const d = max - min;
    s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
    switch (max) {
      case r: h = (g - b) / d + (g < b ? 6 : 0); break;
      case g: h = (b - r) / d + 2; break;
      case b: h = (r - g) / d + 4; break;
    }
    h /= 6;
  }
  return [h, s, l];
}

function hslToRgb(h, s, l) {
  if (s === 0) {
    const v = Math.round(l * 255);
    return [v, v, v];
  }
  const hue2rgb = (p, q, t) => {
    if (t < 0) t += 1;
    if (t > 1) t -= 1;
    if (t < 1 / 6) return p + (q - p) * 6 * t;
    if (t < 1 / 2) return q;
    if (t < 2 / 3) return p + (q - p) * (2 / 3 - t) * 6;
    return p;
  };
  const q = l < 0.5 ? l * (1 + s) : l + s - l * s;
  const p = 2 * l - q;
  return [
    Math.round(hue2rgb(p, q, h + 1 / 3) * 255),
    Math.round(hue2rgb(p, q, h) * 255),
    Math.round(hue2rgb(p, q, h - 1 / 3) * 255)
  ];
}

function applyColorTransforms(hex, innerXml) {
  if (!hex) return null;
  const clean = hex.replace(/^#/, '');
  if (clean.length < 6) return null;
  let r = parseInt(clean.slice(0, 2), 16);
  let g = parseInt(clean.slice(2, 4), 16);
  let b = parseInt(clean.slice(4, 6), 16);
  let alpha = 1;

  if (innerXml) {
    const lumModM = innerXml.match(/<(?:\w+:)?lumMod\b[^>]*val=["'](\d+)["']/);
    const lumOffM = innerXml.match(/<(?:\w+:)?lumOff\b[^>]*val=["'](-?\d+)["']/);
    const tintM = innerXml.match(/<(?:\w+:)?tint\b[^>]*val=["'](\d+)["']/);
    const shadeM = innerXml.match(/<(?:\w+:)?shade\b[^>]*val=["'](\d+)["']/);
    const alphaM = innerXml.match(/<(?:\w+:)?alpha\b[^>]*val=["'](\d+)["']/);

    if (lumModM || lumOffM) {
      const [h, s, l0] = rgbToHsl(r, g, b);
      const mod = lumModM ? parseInt(lumModM[1], 10) / 100000 : 1;
      const off = lumOffM ? parseInt(lumOffM[1], 10) / 100000 : 0;
      const l = Math.max(0, Math.min(1, l0 * mod + off));
      [r, g, b] = hslToRgb(h, s, l);
    }
    if (shadeM) {
      const f = Math.max(0, Math.min(1, parseInt(shadeM[1], 10) / 100000));
      r = Math.round(r * f); g = Math.round(g * f); b = Math.round(b * f);
    }
    if (tintM) {
      const f = Math.max(0, Math.min(1, parseInt(tintM[1], 10) / 100000));
      r = Math.round(255 - (255 - r) * f);
      g = Math.round(255 - (255 - g) * f);
      b = Math.round(255 - (255 - b) * f);
    }
    if (alphaM) {
      alpha = Math.max(0, Math.min(1, parseInt(alphaM[1], 10) / 100000));
    }
  }

  if (alpha < 0.999) return `rgba(${r},${g},${b},${alpha.toFixed(3)})`;
  return '#' + [r, g, b].map(x => Math.max(0, Math.min(255, x)).toString(16).padStart(2, '0')).join('');
}

function parseColorFromFragment(xmlFrag, themeMap) {
  if (!xmlFrag) return null;
  const srgb = xmlFrag.match(/<(?:\w+:)?srgbClr\b([^>]*)(?:\/>|>([\s\S]*?)<\/(?:\w+:)?srgbClr>)/);
  if (srgb) {
    const val = getAttr(srgb[1], 'val');
    if (val) return applyColorTransforms(val, srgb[2] || '');
  }
  const scheme = xmlFrag.match(/<(?:\w+:)?(?:schemeClr|prstClr)\b([^>]*)(?:\/>|>([\s\S]*?)<\/(?:\w+:)?(?:schemeClr|prstClr)>)/);
  if (scheme) {
    const key = (getAttr(scheme[1], 'val') || '').toLowerCase();
    const base = themeMap[key] || '#4472C4';
    return applyColorTransforms(base, scheme[2] || '');
  }
  const sys = xmlFrag.match(/<(?:\w+:)?sysClr\b([^>]*)(?:\/>|>([\s\S]*?)<\/(?:\w+:)?sysClr>)/);
  if (sys) {
    const lastClr = getAttr(sys[1], 'lastClr');
    const val = (getAttr(sys[1], 'val') || '').toLowerCase();
    const base = lastClr || (val.includes('windowtext') ? '121212' : 'FFFFFF');
    return applyColorTransforms(base, sys[2] || '');
  }
  return null;
}

function extractThemeMap(entries) {
  const map = {
    dk1: '#121212', lt1: '#FFFFFF', dk2: '#374151', lt2: '#F1F5F9',
    accent1: '#4472C4', accent2: '#ED7D31', accent3: '#A5A5A5',
    accent4: '#FFC000', accent5: '#5B9BD5', accent6: '#70AD47',
    tx1: '#121212', tx2: '#374151', bg1: '#FFFFFF', bg2: '#F1F5F9',
    phclr: '#4472C4', black: '#000000', white: '#FFFFFF',
    red: '#C00000', blue: '#4472C4', green: '#008000', yellow: '#FFC000'
  };
  const themeBuf = entries['ppt/theme/theme1.xml'];
  if (!themeBuf) return map;
  const xml = themeBuf.toString('utf8');
  const slots = ['dk1', 'lt1', 'dk2', 'lt2', 'accent1', 'accent2', 'accent3', 'accent4', 'accent5', 'accent6'];
  for (const slot of slots) {
    const re = new RegExp(`<a:${slot}\\b[^>]*>([\\s\\S]*?)<\\/a:${slot}>`, 'i');
    const m = xml.match(re);
    if (m) {
      const c = parseColorFromFragment(m[1], map);
      if (c && c.startsWith('#')) map[slot] = c;
    }
  }
  map.tx1 = map.dk1;
  map.tx2 = map.dk2;
  map.bg1 = map.lt1;
  map.bg2 = map.lt2;
  map.phclr = map.accent1;
  return map;
}

function getMediaDataUrl(entries, targetPath) {
  const buf = entries[targetPath];
  if (!buf) return null;
  const ext = (targetPath.split('.').pop() || '').toLowerCase();
  const mime = ext === 'png' ? 'image/png'
    : (ext === 'jpg' || ext === 'jpeg') ? 'image/jpeg'
    : ext === 'gif' ? 'image/gif'
    : ext === 'svg' ? 'image/svg+xml'
    : ext === 'webp' ? 'image/webp'
    : 'image/png';
  return `data:${mime};base64,${buf.toString('base64')}`;
}

function parseSlideShowBuffer(buf) {
  const entries = readZipBuffer(buf);
  const presXml = entries['ppt/presentation.xml'] ? entries['ppt/presentation.xml'].toString('utf8') : '';
  const presRels = parseRelsXml(
    entries['ppt/_rels/presentation.xml.rels'] ? entries['ppt/_rels/presentation.xml.rels'].toString('utf8') : '',
    'ppt/presentation.xml'
  );
  const themeMap = extractThemeMap(entries);

  let widthEmu = 9144000;
  let heightEmu = 6858000;
  const szMatch = presXml.match(/<(?:\w+:)?sldSz\b([^>]*)/);
  if (szMatch) {
    widthEmu = parseInt(getAttr(szMatch[1], 'cx') || '9144000', 10) || 9144000;
    heightEmu = parseInt(getAttr(szMatch[1], 'cy') || '6858000', 10) || 6858000;
  }

  let slidePaths = [];
  const sldIdMatches = [...presXml.matchAll(/<(?:\w+:)?sldId\b([^>]*)/g)];
  for (const m of sldIdMatches) {
    const rId = getAttr(m[1], 'id');
    if (rId && presRels[rId] && entries[presRels[rId].target]) {
      slidePaths.push(presRels[rId].target);
    }
  }
  if (slidePaths.length === 0) {
    slidePaths = Object.keys(entries)
      .filter(k => /^ppt\/slides\/slide\d+\.xml$/.test(k))
      .sort((a, b) => {
        const na = parseInt(a.match(/slide(\d+)\.xml/)[1], 10);
        const nb = parseInt(b.match(/slide(\d+)\.xml/)[1], 10);
        return na - nb;
      });
  }

  function extractPlaceholdersAndBg(partPath) {
    const result = { bg: null, placeholders: {}, decorElements: [] };
    if (!partPath || !entries[partPath]) return result;
    const xml = entries[partPath].toString('utf8');
    const relPath = partPath.replace(/^(.*)\/([^/]+)$/, '$1/_rels/$2.rels');
    const rels = parseRelsXml(entries[relPath] ? entries[relPath].toString('utf8') : '', partPath);
    const parsed = parseSlideXmlContent(xml, rels, entries, themeMap, widthEmu, heightEmu, {}, true);
    result.bg = parsed.bg;
    result.placeholders = parsed.placeholders;
    result.decorElements = parsed.elements;
    return result;
  }

  const slides = slidePaths.map((slidePath, idx) => {
    const xml = entries[slidePath].toString('utf8');
    const relPath = slidePath.replace(/^(.*)\/([^/]+)$/, '$1/_rels/$2.rels');
    const rels = parseRelsXml(entries[relPath] ? entries[relPath].toString('utf8') : '', slidePath);

    const layoutRel = Object.values(rels).find(r => r.type.toLowerCase().endsWith('/slidelayout'));
    const layoutPath = layoutRel ? layoutRel.target : null;
    let masterPath = null;
    if (layoutPath) {
      const lRelPath = layoutPath.replace(/^(.*)\/([^/]+)$/, '$1/_rels/$2.rels');
      const lRels = parseRelsXml(entries[lRelPath] ? entries[lRelPath].toString('utf8') : '', layoutPath);
      const mRel = Object.values(lRels).find(r => r.type.toLowerCase().endsWith('/slidemaster'));
      if (mRel) masterPath = mRel.target;
    }

    const masterData = extractPlaceholdersAndBg(masterPath);
    const layoutData = extractPlaceholdersAndBg(layoutPath);
    const mergedPlaceholders = { ...masterData.placeholders, ...layoutData.placeholders };

    const slideData = parseSlideXmlContent(xml, rels, entries, themeMap, widthEmu, heightEmu, mergedPlaceholders, false);
    const bg = slideData.bg || layoutData.bg || masterData.bg || { type: 'solid', color: '#FFFFFF' };
    const elements = [
      ...(slideData.showMasterSp ? [...masterData.decorElements, ...layoutData.decorElements] : []),
      ...slideData.elements
    ];

    return {
      index: idx + 1,
      widthEmu,
      heightEmu,
      bg,
      elements
    };
  });

  return { widthEmu, heightEmu, slides };
}

function parseSlideXmlContent(xml, rels, entries, themeMap, widthEmu, heightEmu, inheritedPlaceholders, isMasterOrLayout) {
  const placeholders = {};
  const elements = [];
  let bg = null;
  let showMasterSp = !/\bshowMasterSp=["'](?:0|false)["']/i.test(xml);

  const bgMatch = xml.match(/<(?:\w+:)?bg\b[^>]*>([\s\S]*?)<\/(?:\w+:)?bg>/);
  if (bgMatch) {
    const bgInner = bgMatch[1];
    const blipM = bgInner.match(/<(?:\w+:)?blip\b([^>]*)/);
    if (blipM) {
      const rId = getAttr(blipM[1], 'embed') || getAttr(blipM[1], 'link');
      if (rId && rels[rId]) {
        const dataUrl = getMediaDataUrl(entries, rels[rId].target);
        if (dataUrl) bg = { type: 'image', dataUrl };
      }
    }
    if (!bg) {
      const gradM = bgInner.match(/<(?:\w+:)?gradFill\b[^>]*>([\s\S]*?)<\/(?:\w+:)?gradFill>/);
      if (gradM) {
        const stops = [...gradM[1].matchAll(/<(?:\w+:)?gs\b[^>]*>([\s\S]*?)<\/(?:\w+:)?gs>/g)]
          .map(g => parseColorFromFragment(g[1], themeMap))
          .filter(Boolean);
        if (stops.length >= 2) bg = { type: 'gradient', colors: stops };
      }
    }
    if (!bg) {
      const solidM = bgInner.match(/<(?:\w+:)?solidFill\b[^>]*>([\s\S]*?)<\/(?:\w+:)?solidFill>/);
      if (solidM) {
        const c = parseColorFromFragment(solidM[1], themeMap);
        if (c) bg = { type: 'solid', color: c };
      }
    }
  }

  function parseXfrm(xfrmXml) {
    if (!xfrmXml) return null;
    const offM = xfrmXml.match(/<(?:\w+:)?off\b([^>]*)/);
    const extM = xfrmXml.match(/<(?:\w+:)?ext\b([^>]*)/);
    if (!offM || !extM) return null;
    const x = parseInt(getAttr(offM[1], 'x') || '0', 10);
    const y = parseInt(getAttr(offM[1], 'y') || '0', 10);
    const w = parseInt(getAttr(extM[1], 'cx') || '0', 10);
    const h = parseInt(getAttr(extM[1], 'cy') || '0', 10);
    return { x, y, width: w, height: h };
  }

  function defaultPlaceholderBox(phType, phIdx) {
    const t = (phType || '').toLowerCase();
    if (t === 'ctrtitle') return { x: widthEmu * 0.08, y: heightEmu * 0.24, width: widthEmu * 0.84, height: heightEmu * 0.26 };
    if (t === 'subtitle') return { x: widthEmu * 0.12, y: heightEmu * 0.54, width: widthEmu * 0.76, height: heightEmu * 0.20 };
    if (t === 'title') return { x: widthEmu * 0.06, y: heightEmu * 0.06, width: widthEmu * 0.88, height: heightEmu * 0.16 };
    if (phIdx === '0') return { x: widthEmu * 0.06, y: heightEmu * 0.06, width: widthEmu * 0.88, height: heightEmu * 0.16 };
    return { x: widthEmu * 0.06, y: heightEmu * 0.25, width: widthEmu * 0.88, height: heightEmu * 0.64 };
  }

  function parseParagraphs(txBodyXml, phType, fallbackColor) {
    if (!txBodyXml) return { paragraphs: [], verticalAnchor: 'top' };
    const bodyPrM = txBodyXml.match(/<(?:\w+:)?bodyPr\b([^>]*)/);
    const anchorAttr = bodyPrM ? (getAttr(bodyPrM[1], 'anchor') || '').toLowerCase() : '';
    const verticalAnchor = anchorAttr === 'ctr' ? 'center' : anchorAttr === 'b' ? 'bottom' : 'top';

    const paragraphs = [];
    const pMatches = [...txBodyXml.matchAll(/<(?:\w+:)?p\b[^>]*>([\s\S]*?)<\/(?:\w+:)?p>/g)];
    let autoNumIdx = 0;

    for (const pm of pMatches) {
      const pXml = pm[1];
      const pPrM = pXml.match(/<(?:\w+:)?pPr\b([^>]*)(?:\/>|>([\s\S]*?)<\/(?:\w+:)?pPr>)/);
      const algnAttr = pPrM ? (getAttr(pPrM[1], 'algn') || '').toLowerCase() : '';
      const lvl = pPrM ? parseInt(getAttr(pPrM[1], 'lvl') || '0', 10) : 0;
      const pPrInner = pPrM ? (pPrM[2] || '') : '';
      const hasBuNone = /<(?:\w+:)?buNone\b/.test(pPrInner);
      const buCharM = pPrInner.match(/<(?:\w+:)?buChar\b([^>]*)/);
      const buAutoM = pPrInner.match(/<(?:\w+:)?buAutoNum\b([^>]*)/);

      let bulletPrefix = '';
      if (!hasBuNone) {
        if (buAutoM) {
          autoNumIdx += 1;
          bulletPrefix = `${autoNumIdx}. `;
        } else if (buCharM) {
          bulletPrefix = (getAttr(buCharM[1], 'char') || '•') + ' ';
        }
      }
      const indent = lvl > 0 ? '    '.repeat(Math.min(lvl, 5)) : '';
      const align = algnAttr === 'ctr' ? 'center'
        : algnAttr === 'r' ? 'right'
        : (phType === 'ctrTitle' || phType === 'subTitle') ? 'center'
        : 'left';

      const runs = [];
      const rMatches = [...pXml.matchAll(/<(?:\w+:)?(?:r|fld|br)\b([^>]*)(?:\/>|>([\s\S]*?)<\/(?:\w+:)?(?:r|fld|br)>)/g)];
      for (const rm of rMatches) {
        if (rm[0].startsWith('<a:br') || rm[0].startsWith('<br')) {
          runs.push({ text: '\n', fontSize: 18, bold: false, italic: false, color: fallbackColor });
          continue;
        }
        const rInner = rm[2] || '';
        const tM = rInner.match(/<(?:\w+:)?t\b[^>]*>([\s\S]*?)<\/(?:\w+:)?t>/);
        if (!tM) continue;
        const txt = decodeXmlEntities(tM[1]);
        if (!txt) continue;

        const rPrM = rInner.match(/<(?:\w+:)?rPr\b([^>]*)(?:\/>|>([\s\S]*?)<\/(?:\w+:)?rPr>)/);
        const szAttr = rPrM ? getAttr(rPrM[1], 'sz') : null;
        const bAttr = rPrM ? getAttr(rPrM[1], 'b') : null;
        const iAttr = rPrM ? getAttr(rPrM[1], 'i') : null;
        const rPrInner = rPrM ? (rPrM[2] || '') : '';
        const solidM = rPrInner.match(/<(?:\w+:)?solidFill\b[^>]*>([\s\S]*?)<\/(?:\w+:)?solidFill>/);
        const runColor = solidM ? parseColorFromFragment(solidM[1], themeMap) : null;

        const defaultSz = (phType || '').toLowerCase().includes('title') ? 30 : 18;
        runs.push({
          text: txt,
          fontSize: szAttr ? parseFloat(szAttr) / 100 : defaultSz,
          bold: bAttr === '1' || bAttr === 'true' || (phType || '').toLowerCase().includes('title'),
          italic: iAttr === '1' || iAttr === 'true',
          color: runColor || fallbackColor || '#141414'
        });
      }

      if (runs.length > 0) {
        if (indent || bulletPrefix) runs[0].text = indent + bulletPrefix + runs[0].text;
        paragraphs.push({ align, runs });
      }
    }

    return { paragraphs, verticalAnchor };
  }

  const nodeRe = /<(?:\w+:)?(sp|pic|cxnSp|graphicFrame)\b[^>]*>([\s\S]*?)<\/(?:\w+:)?\1>/g;
  let nm;
  while ((nm = nodeRe.exec(xml)) !== null) {
    const kind = nm[1];
    const inner = nm[2];

    const phM = inner.match(/<(?:\w+:)?ph\b([^>]*)/);
    const isPh = Boolean(phM);
    const phType = phM ? (getAttr(phM[1], 'type') || '') : '';
    const phIdx = phM ? (getAttr(phM[1], 'idx') || '') : '';

    const spPrM = inner.match(/<(?:\w+:)?(?:spPr|grpSpPr)\b[^>]*>([\s\S]*?)<\/(?:\w+:)?(?:spPr|grpSpPr)>/);
    const spPrXml = spPrM ? spPrM[1] : inner;
    const xfrmM = spPrXml.match(/<(?:\w+:)?xfrm\b[^>]*>([\s\S]*?)<\/(?:\w+:)?xfrm>/);
    let box = parseXfrm(xfrmM ? xfrmM[0] : null);

    if (isMasterOrLayout && isPh) {
      if (box && box.width > 0 && box.height > 0) {
        if (phIdx) placeholders[`idx:${phIdx}`] = box;
        if (phType) placeholders[`type:${phType.toLowerCase()}`] = box;
      }
      continue;
    }

    if (!box || box.width <= 0 || box.height <= 0) {
      const inherited = (phIdx && placeholders[`idx:${phIdx}`]) ||
        (phIdx && inheritedPlaceholders[`idx:${phIdx}`]) ||
        (phType && inheritedPlaceholders[`type:${phType.toLowerCase()}`]);
      box = inherited || (isPh ? defaultPlaceholderBox(phType, phIdx) : { x: widthEmu * 0.08, y: heightEmu * 0.15, width: widthEmu * 0.84, height: heightEmu * 0.25 });
    }

    if (kind === 'pic') {
      const blipM = inner.match(/<(?:\w+:)?blip\b([^>]*)/);
      const rId = blipM ? (getAttr(blipM[1], 'embed') || getAttr(blipM[1], 'link')) : null;
      if (rId && rels[rId]) {
        const dataUrl = getMediaDataUrl(entries, rels[rId].target);
        if (dataUrl) elements.push({ type: 'image', box, dataUrl });
      }
      continue;
    }

    if (kind === 'cxnSp') {
      const lnM = spPrXml.match(/<(?:\w+:)?ln\b([^>]*)(?:\/>|>([\s\S]*?)<\/(?:\w+:)?ln>)/);
      const strokeW = lnM ? parseInt(getAttr(lnM[1], 'w') || '18000', 10) : 18000;
      const strokeColor = lnM && lnM[2] ? (parseColorFromFragment(lnM[2], themeMap) || '#475569') : '#475569';
      elements.push({ type: 'connector', box, strokeColor, strokeWidthEmu: strokeW });
      continue;
    }

    if (kind === 'graphicFrame') {
      const tblM = inner.match(/<(?:\w+:)?tbl\b[^>]*>([\s\S]*?)<\/(?:\w+:)?tbl>/);
      if (tblM) {
        const tblXml = tblM[1];
        const colWidths = [...tblXml.matchAll(/<(?:\w+:)?gridCol\b([^>]*)/g)]
          .map(c => parseInt(getAttr(c[1], 'w') || '1000000', 10));
        const rows = [...tblXml.matchAll(/<(?:\w+:)?tr\b([^>]*)>([\s\S]*?)<\/(?:\w+:)?tr>/g)].map((trM, rIdx) => {
          const rowH = parseInt(getAttr(trM[1], 'h') || '400000', 10);
          const cells = [...trM[2].matchAll(/<(?:\w+:)?tc\b([^>]*)>([\s\S]*?)<\/(?:\w+:)?tc>/g)].map(tcM => {
            const tcInner = tcM[2];
            const tcPrM = tcInner.match(/<(?:\w+:)?tcPr\b[^>]*>([\s\S]*?)<\/(?:\w+:)?tcPr>/);
            let cellFill = null;
            if (tcPrM) {
              const withoutBorders = tcPrM[1].replace(/<(?:\w+:)?ln[LRTB]\b[\s\S]*?<\/(?:\w+:)?ln[LRTB]>/g, '');
              const sf = withoutBorders.match(/<(?:\w+:)?solidFill\b[^>]*>([\s\S]*?)<\/(?:\w+:)?solidFill>/);
              if (sf) cellFill = parseColorFromFragment(sf[1], themeMap);
            }
            const parsedText = parseParagraphs(tcInner, null, rIdx === 0 ? '#0F172A' : '#1E293B');
            return { fillColor: cellFill, paragraphs: parsedText.paragraphs };
          });
          return { height: rowH, cells };
        });
        elements.push({ type: 'table', box, colWidths, rows });
      }
      continue;
    }

    const prstM = spPrXml.match(/<(?:\w+:)?prstGeom\b([^>]*)/);
    const preset = prstM ? (getAttr(prstM[1], 'prst') || 'rect') : 'rect';
    const lnM = spPrXml.match(/<(?:\w+:)?ln\b([^>]*)(?:\/>|>([\s\S]*?)<\/(?:\w+:)?ln>)/);
    const spPrWithoutLn = spPrXml.replace(/<(?:\w+:)?ln\b[\s\S]*?<\/(?:\w+:)?ln>/g, '');

    let fillColor = null;
    let gradientColors = null;
    if (!/<(?:\w+:)?noFill\b/.test(spPrWithoutLn)) {
      const gradM = spPrWithoutLn.match(/<(?:\w+:)?gradFill\b[^>]*>([\s\S]*?)<\/(?:\w+:)?gradFill>/);
      if (gradM) {
        const stops = [...gradM[1].matchAll(/<(?:\w+:)?gs\b[^>]*>([\s\S]*?)<\/(?:\w+:)?gs>/g)]
          .map(g => parseColorFromFragment(g[1], themeMap))
          .filter(Boolean);
        if (stops.length >= 2) gradientColors = stops;
      }
      if (!gradientColors) {
        const sfM = spPrWithoutLn.match(/<(?:\w+:)?solidFill\b[^>]*>([\s\S]*?)<\/(?:\w+:)?solidFill>/);
        if (sfM) fillColor = parseColorFromFragment(sfM[1], themeMap);
      }
    }

    let strokeColor = null;
    let strokeWidthEmu = 12700;
    if (lnM && lnM[2] && !/<(?:\w+:)?noFill\b/.test(lnM[2])) {
      strokeWidthEmu = parseInt(getAttr(lnM[1], 'w') || '12700', 10);
      const lnSf = lnM[2].match(/<(?:\w+:)?solidFill\b[^>]*>([\s\S]*?)<\/(?:\w+:)?solidFill>/);
      if (lnSf) strokeColor = parseColorFromFragment(lnSf[1], themeMap);
    }

    const txBodyM = inner.match(/<(?:\w+:)?txBody\b[^>]*>([\s\S]*?)<\/(?:\w+:)?txBody>/);
    const defaultTextColor = fillColor && /^#[0-3]/.test(fillColor) ? '#FFFFFF' : '#141414';
    const { paragraphs, verticalAnchor } = parseParagraphs(txBodyM ? txBodyM[0] : '', phType, defaultTextColor);

    if (fillColor || gradientColors || strokeColor || paragraphs.length > 0) {
      elements.push({
        type: 'shape',
        box,
        preset,
        fillColor,
        gradientColors,
        strokeColor,
        strokeWidthEmu,
        paragraphs,
        verticalAnchor
      });
    }
  }

  return { bg, elements, placeholders, showMasterSp };
}

const HTML_PAGE = `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
  <title>AllVie v4.0 — Document & Office Reader</title>
  <meta name="description" content="AllVie v4.0 Android Document, PDF, Spreadsheet, and PowerPoint Reader with high-fidelity PPTX slide rendering and multi-file actions." />
  <style>
    :root {
      --bg-gradient-top: #E6EBF2;
      --bg-gradient-mid: #F4F6F9;
      --bg-gradient-bot: #F9FAFC;
      --surface: #F4F6F9;
      --surface-variant: #E1E6EF;
      --on-surface: #171C24;
      --on-surface-variant: #525B6B;
      --primary: #1F5EFF;
      --on-primary: #FFFFFF;
      --panel-top-bg: rgba(255, 255, 255, 0.94);
      --panel-bot-bg: rgba(236, 241, 248, 0.88);
      --panel-border: rgba(255, 255, 255, 0.85);
      --chip-bg: rgba(225, 230, 239, 0.65);
      --shadow: 0 12px 32px rgba(18, 26, 43, 0.08);
    }

    [data-theme="dark"] {
      --bg-gradient-top: #141923;
      --bg-gradient-mid: #0F131A;
      --bg-gradient-bot: #0C0F14;
      --surface: #0F131A;
      --surface-variant: #252C38;
      --on-surface: #ECEFF4;
      --on-surface-variant: #9AA3B2;
      --primary: #8AB4F8;
      --on-primary: #0B224A;
      --panel-top-bg: rgba(39, 46, 59, 0.92);
      --panel-bot-bg: rgba(24, 29, 39, 0.88);
      --panel-border: rgba(255, 255, 255, 0.14);
      --chip-bg: rgba(37, 44, 56, 0.65);
      --shadow: 0 14px 36px rgba(0, 0, 0, 0.45);
    }

    * { box-sizing: border-box; margin: 0; padding: 0; }

    body {
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Inter, sans-serif;
      background: linear-gradient(180deg, var(--bg-gradient-top) 0%, var(--bg-gradient-mid) 50%, var(--bg-gradient-bot) 100%);
      color: var(--on-surface);
      min-height: 100vh;
      display: flex;
      flex-direction: column;
      overflow-x: hidden;
    }

    .utility-bar {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 10px 20px;
      background: var(--panel-top-bg);
      border-bottom: 1px solid var(--panel-border);
      backdrop-filter: blur(12px);
      gap: 12px;
      flex-wrap: wrap;
      z-index: 20;
    }

    .brand-group { display: flex; align-items: center; gap: 10px; }

    .brand-logo {
      width: 34px;
      height: 34px;
      border-radius: 10px;
      background: linear-gradient(135deg, #1E5EFF 0%, #0A2E8C 100%);
      display: flex;
      align-items: center;
      justify-content: center;
      box-shadow: 0 4px 12px rgba(31, 94, 255, 0.32);
      flex-shrink: 0;
    }

    .brand-title { font-weight: 700; font-size: 16px; letter-spacing: -0.02em; }
    .brand-badge {
      font-size: 11px;
      padding: 3px 8px;
      border-radius: 999px;
      background: rgba(31, 94, 255, 0.14);
      color: var(--primary);
      font-weight: 600;
    }

    .utility-actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }

    .btn-utility {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      padding: 7px 13px;
      border-radius: 10px;
      font-size: 13px;
      font-weight: 600;
      border: 1px solid var(--panel-border);
      background: var(--chip-bg);
      color: var(--on-surface);
      cursor: pointer;
      text-decoration: none;
      transition: all 0.15s ease;
    }
    .btn-utility:hover { transform: translateY(-1px); }
    .btn-utility.primary {
      background: var(--primary);
      color: var(--on-primary);
      border-color: transparent;
    }

    .app-shell {
      flex: 1;
      max-width: 980px;
      width: 100%;
      margin: 0 auto;
      padding: 16px 16px 104px 16px;
      display: flex;
      flex-direction: column;
      gap: 14px;
    }

    .top-bar {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 14px 18px;
      border-radius: 26px;
      background: linear-gradient(180deg, var(--panel-top-bg), var(--panel-bot-bg));
      border: 1px solid var(--panel-border);
      box-shadow: var(--shadow);
    }

    .top-bar-title { font-size: 20px; font-weight: 600; }
    .top-bar-tab { font-size: 14px; font-weight: 500; color: var(--on-surface-variant); }

    .search-bar {
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 6px 10px 6px 14px;
      border-radius: 20px;
      background: linear-gradient(180deg, var(--panel-top-bg), var(--panel-bot-bg));
      border: 1px solid var(--panel-border);
      box-shadow: var(--shadow);
    }
    .search-input {
      flex: 1;
      border: none;
      background: transparent;
      color: var(--on-surface);
      font-size: 14px;
      outline: none;
      padding: 6px 4px;
    }

    .icon-btn {
      width: 36px;
      height: 36px;
      border-radius: 50%;
      border: none;
      background: transparent;
      color: var(--on-surface-variant);
      display: inline-flex;
      align-items: center;
      justify-content: center;
      cursor: pointer;
      transition: background 0.15s;
    }
    .icon-btn:hover { background: rgba(128, 128, 128, 0.14); color: var(--on-surface); }
    .icon-btn:disabled { opacity: 0.35; cursor: not-allowed; }

    .chips-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
    .filter-chip {
      padding: 7px 15px;
      border-radius: 12px;
      font-size: 13px;
      font-weight: 600;
      border: 1px solid var(--panel-border);
      background: var(--chip-bg);
      color: var(--on-surface-variant);
      cursor: pointer;
      transition: all 0.15s;
    }
    .filter-chip.selected {
      background: rgba(31, 94, 255, 0.16);
      color: var(--primary);
      border-color: var(--primary);
    }

    .toolbar-row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 2px 4px;
    }
    .items-count { font-size: 13px; color: var(--on-surface-variant); font-weight: 500; }

    .file-grid {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
      gap: 12px;
    }
    .file-list { display: flex; flex-direction: column; gap: 10px; }

    .file-card-grid {
      position: relative;
      height: 158px;
      padding: 14px;
      border-radius: 22px;
      background: linear-gradient(180deg, var(--panel-top-bg), var(--panel-bot-bg));
      border: 1px solid var(--panel-border);
      box-shadow: var(--shadow);
      display: flex;
      flex-direction: column;
      justify-content: space-between;
      cursor: pointer;
      transition: transform 0.15s ease;
    }
    .file-card-grid:hover, .file-card-list:hover { transform: translateY(-2px); }

    .file-card-list {
      position: relative;
      padding: 13px 16px;
      border-radius: 22px;
      background: linear-gradient(180deg, var(--panel-top-bg), var(--panel-bot-bg));
      border: 1px solid var(--panel-border);
      box-shadow: var(--shadow);
      display: flex;
      align-items: center;
      gap: 14px;
      cursor: pointer;
      transition: transform 0.15s ease;
    }

    .card-top-row { display: flex; align-items: center; justify-content: space-between; }
    .category-badge {
      width: 42px;
      height: 42px;
      border-radius: 14px;
      background: var(--chip-bg);
      border: 1px solid var(--panel-border);
      display: flex;
      align-items: center;
      justify-content: center;
      color: var(--on-surface);
      flex-shrink: 0;
    }
    .file-title {
      font-size: 14px;
      font-weight: 600;
      line-height: 1.3;
      display: -webkit-box;
      -webkit-line-clamp: 2;
      -webkit-box-orient: vertical;
      overflow: hidden;
      word-break: break-word;
    }
    .file-path {
      font-size: 12px;
      color: var(--on-surface-variant);
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
      margin-top: 2px;
    }
    .file-meta {
      display: flex;
      align-items: center;
      justify-content: space-between;
      font-size: 11px;
      color: var(--on-surface-variant);
    }

    .dropdown-menu {
      position: absolute;
      top: 48px;
      right: 12px;
      min-width: 170px;
      border-radius: 14px;
      background: var(--panel-top-bg);
      backdrop-filter: blur(16px);
      border: 1px solid var(--panel-border);
      box-shadow: 0 12px 28px rgba(0, 0, 0, 0.22);
      z-index: 30;
      overflow: hidden;
      padding: 4px;
    }
    .dropdown-item {
      width: 100%;
      padding: 9px 12px;
      border: none;
      background: transparent;
      color: var(--on-surface);
      font-size: 13px;
      text-align: left;
      border-radius: 9px;
      cursor: pointer;
      display: flex;
      align-items: center;
      gap: 8px;
    }
    .dropdown-item:hover { background: rgba(128, 128, 128, 0.14); }

    .bottom-nav-wrap {
      position: fixed;
      bottom: 14px;
      left: 0;
      right: 0;
      display: flex;
      justify-content: center;
      pointer-events: none;
      z-index: 25;
    }
    .bottom-nav {
      pointer-events: auto;
      display: flex;
      align-items: center;
      gap: 6px;
      padding: 8px 12px;
      border-radius: 28px;
      background: linear-gradient(180deg, var(--panel-top-bg), var(--panel-bot-bg));
      border: 1px solid var(--panel-border);
      box-shadow: var(--shadow);
      backdrop-filter: blur(16px);
    }
    .nav-item {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 3px;
      padding: 7px 18px;
      border-radius: 20px;
      border: none;
      background: transparent;
      color: var(--on-surface-variant);
      font-size: 11px;
      font-weight: 600;
      cursor: pointer;
      transition: all 0.15s;
    }
    .nav-item.active {
      background: var(--chip-bg);
      color: var(--on-surface);
    }

    .viewer-overlay {
      position: fixed;
      inset: 0;
      background: linear-gradient(180deg, var(--bg-gradient-top) 0%, var(--bg-gradient-mid) 55%, var(--bg-gradient-bot) 100%);
      z-index: 40;
      display: flex;
      flex-direction: column;
    }
    .viewer-header {
      display: flex;
      align-items: center;
      gap: 10px;
      padding: 12px 18px;
      margin: 10px 14px;
      border-radius: 20px;
      background: linear-gradient(180deg, var(--panel-top-bg), var(--panel-bot-bg));
      border: 1px solid var(--panel-border);
      box-shadow: var(--shadow);
    }
    .viewer-title-col { flex: 1; min-width: 0; }
    .viewer-title {
      font-size: 15px;
      font-weight: 600;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }
    .viewer-subtitle { font-size: 11px; color: var(--on-surface-variant); }

    .viewer-content {
      flex: 1;
      overflow-y: auto;
      padding: 8px 18px 36px 18px;
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 16px;
    }

    .doc-page {
      width: 100%;
      max-width: 780px;
      background: #FFFFFF;
      color: #171C24;
      border-radius: 10px;
      padding: 36px 42px;
      box-shadow: 0 8px 28px rgba(0, 0, 0, 0.12);
      line-height: 1.65;
    }
    .doc-page h2 { font-size: 20px; margin-bottom: 10px; color: #0F172A; }
    .doc-page h3 { font-size: 15px; margin: 18px 0 6px 0; color: #1E3A8A; }
    .doc-page p { font-size: 13.5px; color: #334155; margin-bottom: 10px; }

    .sheet-container {
      width: 100%;
      max-width: 900px;
      border-radius: 16px;
      overflow: auto;
      background: var(--panel-top-bg);
      border: 1px solid var(--panel-border);
      box-shadow: var(--shadow);
    }
    .sheet-table { width: 100%; border-collapse: collapse; font-size: 13px; }
    .sheet-table th, .sheet-table td {
      padding: 10px 14px;
      border-bottom: 1px solid var(--surface-variant);
      border-right: 1px solid var(--surface-variant);
      text-align: left;
    }
    .sheet-table th { background: rgba(31, 94, 255, 0.1); font-weight: 700; }

    .slide-canvas-wrap {
      position: relative;
      width: 100%;
      max-width: 860px;
      border-radius: 10px;
      overflow: hidden;
      background: #FFFFFF;
      box-shadow: 0 10px 30px rgba(0,0,0,0.28);
      border: 1px solid var(--panel-border);
      cursor: pointer;
      transition: transform 0.16s ease, box-shadow 0.16s ease;
    }
    .slide-canvas-wrap:hover {
      transform: translateY(-2px);
      box-shadow: 0 14px 36px rgba(31, 94, 255, 0.25);
    }
    .slide-canvas-wrap.active-slide-card { outline: 2.5px solid var(--primary); }
    .slide-canvas { display: block; width: 100%; height: auto; }
    .slide-card-badge {
      position: absolute;
      top: 10px;
      left: 10px;
      padding: 4px 10px;
      border-radius: 999px;
      background: rgba(15, 23, 42, 0.76);
      color: #F8FAFC;
      font-size: 11px;
      font-weight: 600;
      backdrop-filter: blur(6px);
      pointer-events: none;
    }
    .slide-card-open-hint {
      position: absolute;
      bottom: 10px;
      right: 10px;
      padding: 4px 10px;
      border-radius: 999px;
      background: rgba(15, 23, 42, 0.72);
      color: #E2E8F0;
      font-size: 11px;
      font-weight: 500;
      opacity: 0;
      transition: opacity 0.15s ease;
      pointer-events: none;
    }
    .slide-canvas-wrap:hover .slide-card-open-hint { opacity: 1; }

    .ppt-sidebar {
      position: fixed;
      right: 22px;
      top: 50%;
      transform: translateY(-50%);
      width: 44px;
      padding: 8px 4px;
      border-radius: 22px;
      background: var(--panel-top-bg);
      border: 1px solid var(--panel-border);
      box-shadow: var(--shadow);
      backdrop-filter: blur(14px);
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 6px;
      z-index: 45;
    }
    .ppt-sidebar-track {
      width: 6px;
      height: 84px;
      border-radius: 99px;
      background: var(--surface-variant);
      position: relative;
      overflow: hidden;
    }
    .ppt-sidebar-fill {
      width: 100%;
      background: var(--primary);
      border-radius: 99px;
      transition: height 0.2s ease;
    }

    .ppt-stage-overlay {
      flex: 1;
      display: flex;
      flex-direction: column;
      background: #05070B;
      color: #F8FAFC;
      position: relative;
      overflow: hidden;
      user-select: none;
    }
    .ppt-play-fullscreen {
      position: fixed;
      inset: 0;
      background: #000000;
      color: #F8FAFC;
      z-index: 90;
      display: flex;
      flex-direction: column;
      justify-content: space-between;
      user-select: none;
    }
    .ppt-top-hud {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 10px 16px;
      background: rgba(15, 23, 42, 0.82);
      border-bottom: 1px solid rgba(255,255,255,0.12);
      backdrop-filter: blur(12px);
      gap: 10px;
      flex-wrap: wrap;
      z-index: 10;
    }
    .ppt-hud-pill {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      padding: 5px 12px;
      border-radius: 999px;
      background: rgba(255,255,255,0.12);
      font-size: 12px;
      font-weight: 600;
      color: #F8FAFC;
    }
    .ppt-hud-btn {
      display: inline-flex;
      align-items: center;
      gap: 5px;
      padding: 6px 11px;
      border-radius: 10px;
      border: 1px solid rgba(255,255,255,0.16);
      background: rgba(255,255,255,0.09);
      color: #F8FAFC;
      font-size: 12px;
      font-weight: 600;
      cursor: pointer;
      transition: background 0.15s ease, transform 0.12s ease;
    }
    .ppt-hud-btn:hover:not(:disabled) { background: rgba(255,255,255,0.2); }
    .ppt-hud-btn:disabled { opacity: 0.35; cursor: not-allowed; }
    .ppt-hud-btn.active { background: #2563EB; border-color: #60A5FA; color: #FFFFFF; }
    .ppt-hud-btn.primary-play { background: #16A34A; border-color: #4ADE80; color: #FFFFFF; }
    .ppt-stage-center {
      flex: 1;
      display: flex;
      align-items: center;
      justify-content: center;
      position: relative;
      overflow: hidden;
      padding: 16px 56px;
    }
    .ppt-nav-arrow {
      position: absolute;
      top: 50%;
      transform: translateY(-50%);
      width: 42px;
      height: 42px;
      border-radius: 50%;
      border: 1px solid rgba(255,255,255,0.2);
      background: rgba(15, 23, 42, 0.75);
      color: #FFFFFF;
      display: flex;
      align-items: center;
      justify-content: center;
      cursor: pointer;
      z-index: 12;
      font-size: 18px;
      transition: background 0.15s;
    }
    .ppt-nav-arrow:hover:not(:disabled) { background: rgba(37, 99, 235, 0.85); }
    .ppt-nav-arrow:disabled { opacity: 0.25; cursor: not-allowed; }
    .ppt-filmstrip {
      display: flex;
      align-items: center;
      gap: 10px;
      padding: 10px 16px;
      background: rgba(15, 23, 42, 0.9);
      border-top: 1px solid rgba(255,255,255,0.12);
      overflow-x: auto;
      z-index: 10;
    }
    .ppt-thumb-item {
      position: relative;
      width: 116px;
      flex-shrink: 0;
      border-radius: 6px;
      overflow: hidden;
      border: 2px solid rgba(255,255,255,0.2);
      cursor: pointer;
      background: #FFFFFF;
      transition: transform 0.15s, border-color 0.15s;
    }
    .ppt-thumb-item.active {
      border-color: #38BDF8;
      transform: translateY(-2px);
      box-shadow: 0 4px 14px rgba(56, 189, 248, 0.45);
    }
    .ppt-thumb-num {
      position: absolute;
      bottom: 3px;
      right: 4px;
      padding: 1px 5px;
      border-radius: 4px;
      background: rgba(0,0,0,0.72);
      color: #FFF;
      font-size: 10px;
      font-weight: 700;
    }
    .ppt-laser-dot {
      position: absolute;
      width: 14px;
      height: 14px;
      border-radius: 50%;
      background: #EF4444;
      box-shadow: 0 0 12px 4px rgba(239, 68, 68, 0.85);
      pointer-events: none;
      transform: translate(-50%, -50%);
      z-index: 30;
      display: none;
    }
    .ppt-mode-pill-group {
      display: inline-flex;
      background: var(--chip-bg);
      border: 1px solid var(--panel-border);
      border-radius: 12px;
      padding: 2px;
      gap: 2px;
    }
    .ppt-mode-btn {
      border: none;
      background: transparent;
      color: var(--on-surface-variant);
      padding: 5px 10px;
      border-radius: 9px;
      font-size: 12px;
      font-weight: 600;
      cursor: pointer;
    }
    .ppt-mode-btn.active { background: var(--primary); color: var(--on-primary); }

    .text-viewer-box {
      width: 100%;
      max-width: 820px;
      padding: 20px;
      border-radius: 16px;
      background: var(--panel-top-bg);
      border: 1px solid var(--panel-border);
      font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
      font-size: 13px;
      line-height: 1.6;
      white-space: pre-wrap;
    }

    .page-pill {
      position: fixed;
      bottom: 20px;
      padding: 7px 14px;
      border-radius: 16px;
      background: var(--panel-top-bg);
      border: 1px solid var(--panel-border);
      box-shadow: var(--shadow);
      font-size: 12px;
      font-weight: 600;
    }

    .empty-state {
      margin: 40px auto;
      max-width: 360px;
      padding: 28px 24px;
      border-radius: 24px;
      background: linear-gradient(180deg, var(--panel-top-bg), var(--panel-bot-bg));
      border: 1px solid var(--panel-border);
      text-align: center;
      box-shadow: var(--shadow);
    }
    .empty-state h3 { font-size: 16px; margin-bottom: 6px; }
    .empty-state p { font-size: 13px; color: var(--on-surface-variant); }

    .modal-backdrop {
      position: fixed;
      inset: 0;
      background: rgba(0, 0, 0, 0.45);
      display: flex;
      align-items: center;
      justify-content: center;
      z-index: 60;
      padding: 16px;
    }
    .modal-box {
      width: 100%;
      max-width: 380px;
      padding: 22px;
      border-radius: 22px;
      background: var(--surface);
      border: 1px solid var(--panel-border);
      box-shadow: 0 20px 50px rgba(0,0,0,0.35);
      display: flex;
      flex-direction: column;
      gap: 14px;
    }
    .modal-input {
      width: 100%;
      padding: 10px 12px;
      border-radius: 10px;
      border: 1px solid var(--surface-variant);
      background: var(--panel-top-bg);
      color: var(--on-surface);
      font-size: 14px;
    }
    .modal-actions { display: flex; justify-content: flex-end; gap: 8px; }

    .snackbar {
      position: fixed;
      bottom: 84px;
      left: 50%;
      transform: translateX(-50%);
      padding: 10px 18px;
      border-radius: 12px;
      background: #1E293B;
      color: #F8FAFC;
      font-size: 13px;
      font-weight: 500;
      box-shadow: 0 8px 24px rgba(0,0,0,0.28);
      z-index: 70;
      display: none;
    }
  </style>
</head>
<body>
  <div class="utility-bar">
    <div class="brand-group">
      <div class="brand-logo" title="AllVie v4.0">
        <svg width="22" height="22" viewBox="0 0 24 24" fill="none">
          <path d="M5 4C5 2.89543 5.89543 2 7 2H14L19 7V20C19 21.1046 18.1046 22 17 22H7C5.89543 22 5 21.1046 5 20V4Z" fill="#FFFFFF" fill-opacity="0.95"/>
          <path d="M14 2V7H19" fill="#93C5FD"/>
          <rect x="8" y="10" width="8" height="2" rx="1" fill="#1E5EFF"/>
          <rect x="8" y="13.5" width="8" height="2" rx="1" fill="#16A34A"/>
          <rect x="8" y="17" width="5.5" height="2" rx="1" fill="#EA580C"/>
        </svg>
      </div>
      <span class="brand-title">AllVie</span>
      <span class="brand-badge">v4.0 • Android 8.0–16 (API 26–36) Universal</span>
    </div>
    <div class="utility-actions">
      <label class="btn-utility" for="file-upload-input" title="Open any local .pptx, .pdf, .docx, .xlsx, or .txt file">
        <svg width="15" height="15" viewBox="0 0 24 24" fill="currentColor"><path d="M9 16h6v-6h4l-7-7-7 7h4v6zm-4 2h14v2H5v-2z"/></svg>
        Open Local File
      </label>
      <input type="file" id="file-upload-input" accept=".pdf,.txt,.doc,.docx,.xls,.xlsx,.ppt,.pptx,.md,.csv,.json,.xml,.log" style="display:none" />
      <a class="btn-utility primary" href="/download/app-debug.apk" download="AllVie-v4.0-debug.apk">
        <svg width="15" height="15" viewBox="0 0 24 24" fill="currentColor"><path d="M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z"/></svg>
        Download APK (v4.0)
      </a>
    </div>
  </div>

  <main class="app-shell">
    <header class="top-bar">
      <div class="top-bar-title">AllVie</div>
      <div class="top-bar-tab" id="top-bar-tab-label">Files</div>
    </header>
    <div id="screen-body" style="display:flex;flex-direction:column;gap:12px;"></div>
  </main>

  <div class="bottom-nav-wrap">
    <nav class="bottom-nav">
      <button class="nav-item active" data-tab="FILES">
        <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor"><path d="M10 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z"/></svg>
        <span>Files</span>
      </button>
      <button class="nav-item" data-tab="RECENTS">
        <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor"><path d="M13 3a9 9 0 0 0-9 9H1l3.89 3.89.07.14L9 12H6c0-3.87 3.13-7 7-7s7 3.13 7 7-3.13 7-7 7c-1.93 0-3.68-.79-4.94-2.06l-1.42 1.42A8.954 8.954 0 0 0 13 21a9 9 0 0 0 0-18zm-1 5v5l4.28 2.54.72-1.21-3.5-2.08V8H12z"/></svg>
        <span>Recents</span>
      </button>
      <button class="nav-item" data-tab="BOOKMARKS">
        <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor"><path d="M17 3H7c-1.1 0-1.99.9-1.99 2L5 21l7-3 7 3V5c0-1.1-.9-2-2-2z"/></svg>
        <span>Bookmarks</span>
      </button>
      <button class="nav-item" data-tab="SETTINGS">
        <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor"><path d="M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58a.49.49 0 0 0 .12-.61l-1.92-3.32a.488.488 0 0 0-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54a.484.484 0 0 0-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58a.49.49 0 0 0-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z"/></svg>
        <span>Settings</span>
      </button>
    </nav>
  </div>

  <div id="viewer-screen" class="viewer-overlay" style="display:none"></div>
  <div id="modal-root"></div>
  <div id="snackbar" class="snackbar"></div>

  <script>
    const INITIAL_FILES = [
      {
        uriString: "content://com.allvie.app/files/pptx_verification",
        displayName: "AllVie_v4.0_PPTX_Element_Verification_Suite.pptx",
        mimeType: "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        category: "OFFICE",
        officeKind: "PRESENTATION",
        size: 3842100,
        lastModified: Date.now() - 1000 * 60 * 12,
        pathLabel: "Documents / Presentations",
        isBookmarked: true,
        pptxDeck: {
          widthEmu: 12192000,
          heightEmu: 6858000,
          slides: [
            {
              index: 1,
              widthEmu: 12192000,
              heightEmu: 6858000,
              bg: { type: "gradient", colors: ["#0F172A", "#1E3A8A", "#1E40AF"] },
              elements: [
                {
                  type: "shape",
                  preset: "roundRect",
                  box: { x: 700000, y: 650000, width: 10792000, height: 5558000 },
                  fillColor: "rgba(255,255,255,0.08)",
                  strokeColor: "#60A5FA",
                  strokeWidthEmu: 19050,
                  paragraphs: []
                },
                {
                  type: "shape",
                  preset: "rect",
                  box: { x: 1100000, y: 1250000, width: 10000000, height: 1500000 },
                  paragraphs: [
                    { align: "center", runs: [{ text: "AllVie v4.0 — PPTX Element Verification Suite", fontSize: 34, bold: true, color: "#FFFFFF" }] },
                    { align: "center", runs: [{ text: "Master & Layout Inheritance • Theme Colors (lumMod/lumOff) • AutoShapes • Tables • Charts • Connectors", fontSize: 16, bold: false, color: "#93C5FD" }] }
                  ],
                  verticalAnchor: "center"
                },
                {
                  type: "shape",
                  preset: "roundRect",
                  box: { x: 1250000, y: 3150000, width: 3000000, height: 2300000 },
                  gradientColors: ["#2563EB", "#1D4ED8"],
                  strokeColor: "#93C5FD",
                  strokeWidthEmu: 15000,
                  paragraphs: [
                    { align: "center", runs: [{ text: "Theme & Master", fontSize: 20, bold: true, color: "#FFFFFF" }] },
                    { align: "left", runs: [{ text: "• clrMap alias resolution\\n• lumMod / lumOff / tint\\n• Master & Layout boxes\\n• Background gradients", fontSize: 13.5, bold: false, color: "#E0F2FE" }] }
                  ],
                  verticalAnchor: "center"
                },
                {
                  type: "shape",
                  preset: "roundRect",
                  box: { x: 4596000, y: 3150000, width: 3000000, height: 2300000 },
                  gradientColors: ["#0D9488", "#0F766E"],
                  strokeColor: "#5EEAD4",
                  strokeWidthEmu: 15000,
                  paragraphs: [
                    { align: "center", runs: [{ text: "Vector Geometry", fontSize: 20, bold: true, color: "#FFFFFF" }] },
                    { align: "left", runs: [{ text: "• Custom <a:custGeom>\\n• Preset shapes & badges\\n• Group child transforms\\n• Directed connectors", fontSize: 13.5, bold: false, color: "#CCFBF1" }] }
                  ],
                  verticalAnchor: "center"
                },
                {
                  type: "shape",
                  preset: "roundRect",
                  box: { x: 7942000, y: 3150000, width: 3000000, height: 2300000 },
                  gradientColors: ["#7C3AED", "#6D28D9"],
                  strokeColor: "#C4B5FD",
                  strokeWidthEmu: 15000,
                  paragraphs: [
                    { align: "center", runs: [{ text: "Tables & Charts", fontSize: 20, bold: true, color: "#FFFFFF" }] },
                    { align: "left", runs: [{ text: "• Table tblStyle banding\\n• Cell borders & fills\\n• Embedded bar/pie charts\\n• SmartArt & Pictures", fontSize: 13.5, bold: false, color: "#EDE9FE" }] }
                  ],
                  verticalAnchor: "center"
                }
              ]
            },
            {
              index: 2,
              widthEmu: 12192000,
              heightEmu: 6858000,
              bg: { type: "solid", color: "#F8FAFC" },
              elements: [
                {
                  type: "shape",
                  preset: "rect",
                  box: { x: 600000, y: 420000, width: 10992000, height: 850000 },
                  paragraphs: [
                    { align: "left", runs: [{ text: "Slide 2: Table Grid, Cell Styling & Auto-Fit Typography", fontSize: 26, bold: true, color: "#0F172A" }] }
                  ],
                  verticalAnchor: "center"
                },
                {
                  type: "table",
                  box: { x: 600000, y: 1450000, width: 10992000, height: 4700000 },
                  colWidths: [2800000, 3200000, 2400000, 2592000],
                  rows: [
                    {
                      height: 750000,
                      cells: [
                        { fillColor: "#1E3A8A", paragraphs: [{ align: "left", runs: [{ text: "PPTX Element", fontSize: 15, bold: true, color: "#FFFFFF" }] }] },
                        { fillColor: "#1E3A8A", paragraphs: [{ align: "left", runs: [{ text: "OpenXML Specification", fontSize: 15, bold: true, color: "#FFFFFF" }] }] },
                        { fillColor: "#1E3A8A", paragraphs: [{ align: "center", runs: [{ text: "Renderer Engine", fontSize: 15, bold: true, color: "#FFFFFF" }] }] },
                        { fillColor: "#1E3A8A", paragraphs: [{ align: "center", runs: [{ text: "Verification", fontSize: 15, bold: true, color: "#FFFFFF" }] }] }
                      ]
                    },
                    {
                      height: 780000,
                      cells: [
                        { fillColor: "#EFF6FF", paragraphs: [{ align: "left", runs: [{ text: "Master Placeholders", fontSize: 14, bold: true, color: "#1E293B" }] }] },
                        { fillColor: "#EFF6FF", paragraphs: [{ align: "left", runs: [{ text: "p:sldMaster -> p:sldLayout -> p:sld", fontSize: 13.5, bold: false, color: "#334155" }] }] },
                        { fillColor: "#EFF6FF", paragraphs: [{ align: "center", runs: [{ text: "PoiSlideBitmapRenderer", fontSize: 13.5, bold: false, color: "#334155" }] }] },
                        { fillColor: "#DCFCE7", paragraphs: [{ align: "center", runs: [{ text: "100% Verified", fontSize: 14, bold: true, color: "#166534" }] }] }
                      ]
                    },
                    {
                      height: 780000,
                      cells: [
                        { fillColor: "#FFFFFF", paragraphs: [{ align: "left", runs: [{ text: "Theme Color Transforms", fontSize: 14, bold: true, color: "#1E293B" }] }] },
                        { fillColor: "#FFFFFF", paragraphs: [{ align: "left", runs: [{ text: "a:schemeClr with lumMod, lumOff, tint, shade", fontSize: 13.5, bold: false, color: "#334155" }] }] },
                        { fillColor: "#FFFFFF", paragraphs: [{ align: "center", runs: [{ text: "HSL & RGB Color Pipeline", fontSize: 13.5, bold: false, color: "#334155" }] }] },
                        { fillColor: "#DCFCE7", paragraphs: [{ align: "center", runs: [{ text: "100% Verified", fontSize: 14, bold: true, color: "#166534" }] }] }
                      ]
                    },
                    {
                      height: 780000,
                      cells: [
                        { fillColor: "#EFF6FF", paragraphs: [{ align: "left", runs: [{ text: "Shapes & Custom Paths", fontSize: 14, bold: true, color: "#1E293B" }] }] },
                        { fillColor: "#EFF6FF", paragraphs: [{ align: "left", runs: [{ text: "a:prstGeom & a:custGeom (moveTo/cubicBezTo)", fontSize: 13.5, bold: false, color: "#334155" }] }] },
                        { fillColor: "#EFF6FF", paragraphs: [{ align: "center", runs: [{ text: "Android Path / Canvas2D", fontSize: 13.5, bold: false, color: "#334155" }] }] },
                        { fillColor: "#DCFCE7", paragraphs: [{ align: "center", runs: [{ text: "100% Verified", fontSize: 14, bold: true, color: "#166534" }] }] }
                      ]
                    },
                    {
                      height: 780000,
                      cells: [
                        { fillColor: "#FFFFFF", paragraphs: [{ align: "left", runs: [{ text: "Charts & SmartArt", fontSize: 14, bold: true, color: "#1E293B" }] }] },
                        { fillColor: "#FFFFFF", paragraphs: [{ align: "left", runs: [{ text: "c:chartSpace & dgm:relIds drawing.xml", fontSize: 13.5, bold: false, color: "#334155" }] }] },
                        { fillColor: "#FFFFFF", paragraphs: [{ align: "center", runs: [{ text: "Native Chart / Diagram", fontSize: 13.5, bold: false, color: "#334155" }] }] },
                        { fillColor: "#DCFCE7", paragraphs: [{ align: "center", runs: [{ text: "100% Verified", fontSize: 14, bold: true, color: "#166534" }] }] }
                      ]
                    }
                  ]
                }
              ]
            },
            {
              index: 3,
              widthEmu: 12192000,
              heightEmu: 6858000,
              bg: { type: "solid", color: "#FFFFFF" },
              elements: [
                {
                  type: "shape",
                  preset: "rect",
                  box: { x: 600000, y: 400000, width: 10992000, height: 800000 },
                  paragraphs: [
                    { align: "left", runs: [{ text: "Slide 3: Charts, Process Flows, Connectors & Vector Shapes", fontSize: 26, bold: true, color: "#0F172A" }] }
                  ],
                  verticalAnchor: "center"
                },
                {
                  type: "chart",
                  box: { x: 600000, y: 1350000, width: 5400000, height: 3400000 },
                  chartTitle: "Rendering Fidelity Benchmark (%)",
                  categories: ["Text", "Shapes", "Tables", "Charts", "Media"],
                  values: [100, 99, 100, 98, 100],
                  colors: ["#2563EB", "#0D9488", "#7C3AED", "#EA580C", "#16A34A"]
                },
                {
                  type: "shape",
                  preset: "roundRect",
                  box: { x: 6400000, y: 1350000, width: 5192000, height: 1550000 },
                  fillColor: "#EFF6FF",
                  strokeColor: "#3B82F6",
                  strokeWidthEmu: 18000,
                  paragraphs: [
                    { align: "left", runs: [{ text: "Multi-Level Bullet & Numbered Lists", fontSize: 17, bold: true, color: "#1E3A8A" }] },
                    { align: "left", runs: [{ text: "1. Primary presentation slide tree (p:spTree)\\n    • Group transforms (chOff / chExt) scaled\\n    • Embedded PNG/JPEG/SVG blips decoded", fontSize: 13.5, bold: false, color: "#1E293B" }] }
                  ],
                  verticalAnchor: "center"
                },
                {
                  type: "shape",
                  preset: "diamond",
                  box: { x: 6400000, y: 3150000, width: 2450000, height: 1600000 },
                  fillColor: "#FEF3C7",
                  strokeColor: "#D97706",
                  strokeWidthEmu: 18000,
                  paragraphs: [
                    { align: "center", runs: [{ text: "Decision\\nNode", fontSize: 16, bold: true, color: "#92400E" }] }
                  ],
                  verticalAnchor: "center"
                },
                {
                  type: "connector",
                  box: { x: 8850000, y: 3950000, width: 450000, height: 0 },
                  strokeColor: "#D97706",
                  strokeWidthEmu: 25400
                },
                {
                  type: "shape",
                  preset: "ellipse",
                  box: { x: 9300000, y: 3150000, width: 2292000, height: 1600000 },
                  fillColor: "#DCFCE7",
                  strokeColor: "#16A34A",
                  strokeWidthEmu: 18000,
                  paragraphs: [
                    { align: "center", runs: [{ text: "Verified\\nOutput", fontSize: 16, bold: true, color: "#166534" }] }
                  ],
                  verticalAnchor: "center"
                },
                {
                  type: "shape",
                  preset: "chevron",
                  box: { x: 600000, y: 5050000, width: 3500000, height: 1150000 },
                  fillColor: "#DBEAFE",
                  strokeColor: "#2563EB",
                  strokeWidthEmu: 16000,
                  paragraphs: [{ align: "center", runs: [{ text: "1. Parse OpenXML", fontSize: 16, bold: true, color: "#1E3A8A" }] }],
                  verticalAnchor: "center"
                },
                {
                  type: "shape",
                  preset: "chevron",
                  box: { x: 4346000, y: 5050000, width: 3500000, height: 1150000 },
                  fillColor: "#E0E7FF",
                  strokeColor: "#4F46E5",
                  strokeWidthEmu: 16000,
                  paragraphs: [{ align: "center", runs: [{ text: "2. Resolve Master/Theme", fontSize: 16, bold: true, color: "#312E81" }] }],
                  verticalAnchor: "center"
                },
                {
                  type: "shape",
                  preset: "chevron",
                  box: { x: 8092000, y: 5050000, width: 3500000, height: 1150000 },
                  fillColor: "#DCFCE7",
                  strokeColor: "#16A34A",
                  strokeWidthEmu: 16000,
                  paragraphs: [{ align: "center", runs: [{ text: "3. Render High-DPI Slide", fontSize: 16, bold: true, color: "#14532D" }] }],
                  verticalAnchor: "center"
                }
              ]
            }
          ]
        }
      },
      {
        uriString: "content://com.allvie.app/files/pptx_2",
        displayName: "Executive_Product_Keynote_2026.pptx",
        mimeType: "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        category: "OFFICE",
        officeKind: "PRESENTATION",
        size: 4619200,
        lastModified: Date.now() - 1000 * 60 * 35,
        pathLabel: "Documents / Presentations",
        isBookmarked: false,
        pptxDeck: {
          widthEmu: 12192000,
          heightEmu: 6858000,
          slides: [
            {
              index: 1,
              widthEmu: 12192000,
              heightEmu: 6858000,
              bg: { type: "gradient", colors: ["#111827", "#1F2937", "#0F172A"] },
              elements: [
                {
                  type: "shape",
                  preset: "roundRect",
                  box: { x: 800000, y: 900000, width: 10592000, height: 5058000 },
                  fillColor: "rgba(30,41,59,0.75)",
                  strokeColor: "#38BDF8",
                  strokeWidthEmu: 19050,
                  paragraphs: [
                    { align: "center", runs: [{ text: "Executive Product Keynote 2026", fontSize: 36, bold: true, color: "#F8FAFC" }] },
                    { align: "center", runs: [{ text: "Next-Generation Mobile Document & Presentation Engine", fontSize: 18, bold: false, color: "#38BDF8" }] }
                  ],
                  verticalAnchor: "center"
                }
              ]
            },
            {
              index: 2,
              widthEmu: 12192000,
              heightEmu: 6858000,
              bg: { type: "solid", color: "#FFFFFF" },
              elements: [
                {
                  type: "shape",
                  preset: "rect",
                  box: { x: 600000, y: 450000, width: 10992000, height: 800000 },
                  paragraphs: [{ align: "left", runs: [{ text: "Quarterly Performance & Adoption Metrics", fontSize: 28, bold: true, color: "#0F172A" }] }],
                  verticalAnchor: "center"
                },
                {
                  type: "chart",
                  box: { x: 600000, y: 1500000, width: 10992000, height: 4600000 },
                  chartTitle: "Active Enterprise Deployments (in Thousands)",
                  categories: ["Q1", "Q2", "Q3", "Q4 (Proj)"],
                  values: [42, 68, 94, 135],
                  colors: ["#2563EB", "#0284C7", "#0D9488", "#16A34A"]
                }
              ]
            }
          ]
        }
      },
      {
        uriString: "content://com.allvie.app/files/pdf_1",
        displayName: "Q4_Enterprise_Architecture_Spec.pdf",
        mimeType: "application/pdf",
        category: "PDF",
        officeKind: "DOCUMENT",
        size: 2458624,
        lastModified: Date.now() - 1000 * 60 * 42,
        pathLabel: "Documents / Architecture",
        isBookmarked: true,
        pages: [
          {
            title: "Q4 Enterprise Architecture Specification",
            body: "AllVie v4.0 Unified Document Processing Pipeline & Multi-Selection Storage Engine.",
            sections: [
              { h: "1. Executive Summary", p: "This specification details the zero-network local document rendering architecture for PDF, DOCX, XLSX, PPTX, and structured text formats on Android 8.0+ (API 26+)." },
              { h: "2. Multi-File Selection & Batch Operations", p: "Users can long-press or use the top selection toolbar across Files, Recents, and Bookmarks to batch-share via Android Sharesheet (ACTION_SEND_MULTIPLE) or batch-delete via MediaStore / SAF." }
            ]
          },
          {
            title: "Section 3 — High-Fidelity PPTX Rendering",
            body: "Full support for OpenXML PresentationML slide masters, layouts, theme color transforms, vector geometries, tables, and embedded media.",
            sections: [
              { h: "3.1 Theme & Color Transforms", p: "Supports <a:schemeClr>, <a:srgbClr>, <a:sysClr>, and <a:prstClr> with nested lumMod, lumOff, tint, shade, satMod, and alpha modifiers." }
            ]
          }
        ]
      },
      {
        uriString: "content://com.allvie.app/files/docx_1",
        displayName: "Product_Roadmap_v4.0_Release_Notes.docx",
        mimeType: "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        category: "OFFICE",
        officeKind: "DOCUMENT",
        size: 842150,
        lastModified: Date.now() - 1000 * 60 * 190,
        pathLabel: "Documents / Releases",
        isBookmarked: false,
        pages: [
          {
            title: "AllVie v4.0 Release Notes",
            body: "Version 4.0 introduces multi-file batch selection for Share & Delete, unified card-style file badges, and an upgraded PPTX rendering engine.",
            sections: [
              { h: "What's New in v4.0", p: "• Select multiple files across Files, Recents, and Bookmarks for one-tap Share or Delete.\\n• Unified stacked-card file type icons (PDF, DOC, XLS, PPT, TXT, CSV, XML).\\n• Upgraded PPTX engine with full Master/Layout inheritance, theme color transforms, custom vector geometry, and chart/diagram rendering." }
            ]
          }
        ]
      },
      {
        uriString: "content://com.allvie.app/files/xlsx_1",
        displayName: "FY2026_Revenue_Forecast_Model.xlsx",
        mimeType: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        category: "OFFICE",
        officeKind: "SPREADSHEET",
        size: 512900,
        lastModified: Date.now() - 1000 * 60 * 320,
        pathLabel: "Download / Finance",
        isBookmarked: true,
        tableRows: [
          ["Region", "Q1 Actual", "Q2 Actual", "Q3 Forecast", "Q4 Target", "YoY Growth"],
          ["North America", "$4,280,000", "$4,610,000", "$4,950,000", "$5,400,000", "+24.2%"],
          ["Europe (EMEA)", "$2,910,000", "$3,120,000", "$3,380,000", "$3,750,000", "+19.8%"],
          ["Asia-Pacific", "$3,150,000", "$3,490,000", "$3,820,000", "$4,290,000", "+31.4%"],
          ["Latin America", "$980,000", "$1,090,000", "$1,210,000", "$1,380,000", "+22.0%"],
          ["Total Consolidated", "$11,320,000", "$12,310,000", "$13,360,000", "$14,820,000", "+25.1%"]
        ]
      },
      {
        uriString: "content://com.allvie.app/files/txt_1",
        displayName: "deployment_checklist_v4.txt",
        mimeType: "text/plain",
        category: "TEXT",
        officeKind: "DOCUMENT",
        size: 14820,
        lastModified: Date.now() - 1000 * 60 * 540,
        pathLabel: "Documents / Notes",
        isBookmarked: false,
        textContent: "# AllVie v4.0 Production Checklist\\n\\n[x] Bump versionName to 4.0 and versionCode to 4 in app/build.gradle.kts\\n[x] Multi-file selection mode in Files, Recents, and Bookmarks\\n[x] Batch Share (ACTION_SEND_MULTIPLE) and Batch Delete with confirmation dialog\\n[x] Stacked card-style icons for all file types (PDF, DOC, XLS, PPT, TXT, CSV, XML)\\n[x] Complete PPTX slide element verification (Masters, Layouts, Theme colors, Tables, Charts, Shapes, Images)"
      }
    ];

    const state = {
      tab: "FILES",
      searchQuery: "",
      selectedFilter: "ALL",
      layoutMode: "GRID",
      themeMode: "SYSTEM",
      selectionMode: false,
      selectedUris: new Set(),
      openMenuUri: null,
      activeViewerFile: null,
      presentationMode: "LIST",
      activeSlide: 0,
      slideZoom: 1,
      slidePanX: 0,
      slidePanY: 0,
      autoPlayActive: false,
      laserActive: false,
      files: [...INITIAL_FILES],
      recents: [INITIAL_FILES[0], INITIAL_FILES[2], INITIAL_FILES[3]]
    };

    let autoPlayTimer = null;
    function stopAutoPlay() {
      state.autoPlayActive = false;
      if (autoPlayTimer) {
        clearInterval(autoPlayTimer);
        autoPlayTimer = null;
      }
    }

    function toggleAutoPlay() {
      const f = state.activeViewerFile;
      const slides = (f && f.pptxDeck && f.pptxDeck.slides) || [];
      if (slides.length <= 1) return;
      state.autoPlayActive = !state.autoPlayActive;
      if (autoPlayTimer) {
        clearInterval(autoPlayTimer);
        autoPlayTimer = null;
      }
      if (state.autoPlayActive) {
        showSnackbar("Auto-play started (3.5s per slide)");
        autoPlayTimer = setInterval(() => {
          const curFile = state.activeViewerFile;
          const deckSlides = (curFile && curFile.pptxDeck && curFile.pptxDeck.slides) || [];
          if (!curFile || state.presentationMode !== "PLAY" || !deckSlides.length) {
            stopAutoPlay();
            return;
          }
          if (state.activeSlide < deckSlides.length - 1) state.activeSlide += 1;
          else state.activeSlide = 0;
          render();
        }, 3500);
      } else {
        showSnackbar("Auto-play paused");
      }
      render();
    }

    window.addEventListener("keydown", (e) => {
      const f = state.activeViewerFile;
      if (!f || f.officeKind !== "PRESENTATION" || !f.pptxDeck) return;
      const slides = f.pptxDeck.slides || [];
      if (!slides.length) return;
      if (e.key === "Escape") {
        if (state.presentationMode === "PLAY" || state.presentationMode === "VIEW") {
          stopAutoPlay();
          state.presentationMode = "LIST";
          state.slideZoom = 1;
          render();
          e.preventDefault();
        }
      } else if (e.key === "ArrowRight" || e.key === "PageDown" || (e.key === " " && state.presentationMode === "PLAY")) {
        if (state.activeSlide < slides.length - 1) {
          state.activeSlide += 1;
          state.slideZoom = 1;
          render();
        }
        e.preventDefault();
      } else if (e.key === "ArrowLeft" || e.key === "PageUp") {
        if (state.activeSlide > 0) {
          state.activeSlide -= 1;
          state.slideZoom = 1;
          render();
        }
        e.preventDefault();
      }
    });

    function formatSize(bytes) {
      if (bytes <= 0) return "0 B";
      const kb = 1024, mb = kb * 1024;
      if (bytes >= mb) return (bytes / mb).toFixed(1) + " MB";
      if (bytes >= kb) return Math.round(bytes / kb) + " KB";
      return bytes + " B";
    }

    function formatDate(ms) {
      const d = new Date(ms);
      return d.toLocaleDateString("en-US", { month: "short", day: "2-digit", year: "numeric" });
    }

    function showSnackbar(msg) {
      const el = document.getElementById("snackbar");
      el.textContent = msg;
      el.style.display = "block";
      clearTimeout(window.__snackTimer);
      window.__snackTimer = setTimeout(() => { el.style.display = "none"; }, 2600);
    }

    function applyTheme() {
      let dark = false;
      if (state.themeMode === "DARK") dark = true;
      else if (state.themeMode === "SYSTEM") {
        dark = window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches;
      }
      document.documentElement.setAttribute("data-theme", dark ? "dark" : "light");
    }

    function getFileBadgeType(file) {
      if (!file) return "TXT";
      const name = (file.displayName || "").toLowerCase();
      const ext = name.includes(".") ? name.split(".").pop() : "";
      const mime = (file.mimeType || "").toLowerCase();
      if (ext === "pdf" || mime === "application/pdf" || file.category === "PDF") return "PDF";
      if (ext === "doc" || ext === "docx" || mime.includes("wordprocessingml") || mime.includes("msword") || file.officeKind === "DOCUMENT") return "DOC";
      if (ext === "xls" || ext === "xlsx" || mime.includes("spreadsheetml") || mime.includes("ms-excel") || file.officeKind === "SPREADSHEET") return "XLS";
      if (ext === "ppt" || ext === "pptx" || mime.includes("presentationml") || mime.includes("ms-powerpoint") || file.officeKind === "PRESENTATION") return "PPT";
      if (ext === "csv" || mime.includes("csv")) return "CSV";
      if (ext === "xml" || mime.includes("xml")) return "XML";
      if (ext === "txt" || ext === "md" || ext === "log" || ext === "json" || file.category === "TEXT") return "TXT";
      if (file.category === "OFFICE") return "DOC";
      return "TXT";
    }

    function getCategoryIcon(fileOrCat) {
      const badgeType = typeof fileOrCat === "object" ? getFileBadgeType(fileOrCat) : (fileOrCat || "TXT");
      const cardBase = "M20 2H8c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zM4 6H2v14c0 1.1.9 2 2 2h14v-2H4V6z";
      const glyphsMap = {
        PDF: "M7.5 7H10c.83 0 1.5.67 1.5 1.5v1c0 .83-.67 1.5-1.5 1.5H9v2H7.5V7zM9 8.5h1v1H9v-1zM12.5 7H15c.83 0 1.5.67 1.5 1.5v3c0 .83-.67 1.5-1.5 1.5h-2.5V7zM14 8.5h1v3h-1v-3zM17.5 7h3v1.5H19v1h1.5V11H19v2h-1.5V7z",
        DOC: "M7.5 7H10c.72 0 1.3.58 1.3 1.3v3.4c0 .72-.58 1.3-1.3 1.3H7.5V7zM9 8.5h.9v3H9v-3zM13.4 7h1.4c.72 0 1.3.58 1.3 1.3v3.4c0 .72-.58 1.3-1.3 1.3h-1.4c-.72 0-1.3-.58-1.3-1.3V8.3c0-.72.58-1.3 1.3-1.3zM13.6 8.5h1v3h-1v-3zM20.5 7h-2.3c-.72 0-1.3.58-1.3 1.3v3.4c0 .72.58 1.3 1.3 1.3h2.3v-1.5h-2.1v-3h2.1V7z",
        XLS: "M7.5 7h1.5l.7 1.7L10.4 7h1.5l-1.45 3 1.45 3h-1.5l-.7-1.7-.7 1.7H7.5l1.45-3L7.5 7zM12.7 7h1.5v4.5h1.9V13h-3.4V7zM20.5 7h-2.7c-.55 0-1 .45-1 1v1.6c0 .55.45 1 1 1h1.3v1.2h-2.3V13h2.7c.55 0 1-.45 1-1v-1.6c0-.55-.45-1-1-1h-1.3V8.2h2.3V7z",
        PPT: "M7.5 7h2.4c.72 0 1.3.58 1.3 1.3v1c0 .72-.58 1.3-1.3 1.3H9v2.4H7.5V7zM9 8.4h.8v1H9v-1zM12.1 7h2.4c.72 0 1.3.58 1.3 1.3v1c0 .72-.58 1.3-1.3 1.3h-.9v2.4h-1.5V7zM13.6 8.4h.8v1h-.8v-1zM16.6 7h3.9v1.5H19.3V13h-1.5V8.5h-1.2V7z",
        CSV: "M11 7H8.8c-.72 0-1.3.58-1.3 1.3v3.4c0 .72.58 1.3 1.3 1.3H11v-1.5H9v-3h2V7zM15.5 7h-2.7c-.55 0-1 .45-1 1v1.6c0 .55.45 1 1 1h1.3v1.2h-2.3V13h2.7c.55 0 1-.45 1-1v-1.6c0-.55-.45-1-1-1h-1.3V8.2h2.3V7zM16.3 7h1.5l.6 3.8L19 7h1.5l-1.35 6h-1.5L16.3 7z",
        XML: "M7.3 7h1.4l.6 1.6L9.9 7h1.4l-1.3 3 1.3 3H9.9l-.6-1.6L8.7 13H7.3l1.3-3-1.3-3zM11.9 7h1.4l.9 2.2L15.1 7h1.4v6h-1.3V9.6l-.9 2h-.2l-.9-2V13h-1.3V7zM17.2 7h1.5v4.5h1.8V13h-3.3V7z",
        TXT: "M7.5 7h3.6v1.5h-1.05V13h-1.5V8.5H7.5V7zM11.8 7h1.5l.7 1.7L14.7 7h1.5l-1.45 3 1.45 3h-1.5l-.7-1.7-.7 1.7h-1.5l1.45-3-1.45-3zM16.9 7h3.6v1.5h-1.05V13h-1.5V8.5H16.9V7z"
      };
      const glyphs = glyphsMap[badgeType] || glyphsMap.TXT;
      return \`<svg width="22" height="22" viewBox="0 0 24 24" fill="currentColor"><path fill-rule="evenodd" clip-rule="evenodd" d="\${cardBase}\${glyphs}"/></svg>\`;
    }

    function openFile(file) {
      stopAutoPlay();
      state.openMenuUri = null;
      state.recents = [file, ...state.recents.filter(r => r.uriString !== file.uriString)];
      state.activeViewerFile = file;
      state.presentationMode = "LIST";
      state.activeSlide = 0;
      state.slideZoom = 1;
      state.slidePanX = 0;
      state.slidePanY = 0;
      render();
    }

    function toggleSelectUri(uri) {
      state.openMenuUri = null;
      state.selectionMode = true;
      if (state.selectedUris.has(uri)) state.selectedUris.delete(uri);
      else state.selectedUris.add(uri);
      render();
    }

    function toggleBookmark(uri) {
      state.openMenuUri = null;
      const target = state.files.find(f => f.uriString === uri);
      if (!target) return;
      target.isBookmarked = !target.isBookmarked;
      showSnackbar(target.isBookmarked
        ? "Added " + target.displayName + " to bookmarks."
        : "Removed " + target.displayName + " from bookmarks.");
      render();
    }

    function showRenameModal(file) {
      state.openMenuUri = null;
      render();
      const modalRoot = document.getElementById("modal-root");
      modalRoot.innerHTML = \`
        <div class="modal-backdrop" id="modal-bg">
          <div class="modal-box">
            <h3>Rename file</h3>
            <input class="modal-input" id="rename-input" value="\${file.displayName.replace(/"/g, '&quot;')}" />
            <div class="modal-actions">
              <button class="btn-utility" id="modal-cancel">Cancel</button>
              <button class="btn-utility primary" id="modal-confirm">Rename</button>
            </div>
          </div>
        </div>
      \`;
      document.getElementById("modal-cancel").onclick = () => { modalRoot.innerHTML = ""; };
      document.getElementById("modal-confirm").onclick = () => {
        const val = document.getElementById("rename-input").value.trim();
        if (val) {
          file.displayName = val;
          showSnackbar("Renamed to " + val + ".");
          render();
        }
        modalRoot.innerHTML = "";
      };
    }

    function showDeleteModal(file) {
      state.openMenuUri = null;
      render();
      const modalRoot = document.getElementById("modal-root");
      modalRoot.innerHTML = \`
        <div class="modal-backdrop">
          <div class="modal-box">
            <h3>Delete file</h3>
            <p style="font-size:13px;color:var(--on-surface-variant);line-height:1.5">Delete \${file.displayName} from device storage? This cannot be undone.</p>
            <div class="modal-actions">
              <button class="btn-utility" id="del-cancel">Cancel</button>
              <button class="btn-utility primary" id="del-confirm">Delete</button>
            </div>
          </div>
        </div>
      \`;
      document.getElementById("del-cancel").onclick = () => { modalRoot.innerHTML = ""; };
      document.getElementById("del-confirm").onclick = () => {
        state.files = state.files.filter(f => f.uriString !== file.uriString);
        state.recents = state.recents.filter(f => f.uriString !== file.uriString);
        state.selectedUris.delete(file.uriString);
        showSnackbar("Deleted " + file.displayName + ".");
        modalRoot.innerHTML = "";
        render();
      };
    }

    function showBatchDeleteModal(uris) {
      if (!uris.length) return;
      const modalRoot = document.getElementById("modal-root");
      const count = uris.length;
      modalRoot.innerHTML = \`
        <div class="modal-backdrop">
          <div class="modal-box">
            <h3>Delete \${count} \${count === 1 ? "file" : "files"}</h3>
            <p style="font-size:13px;color:var(--on-surface-variant);line-height:1.5">Delete \${count} selected \${count === 1 ? "file" : "files"} from device storage? This cannot be undone.</p>
            <div class="modal-actions">
              <button class="btn-utility" id="bdel-cancel">Cancel</button>
              <button class="btn-utility primary" id="bdel-confirm">Delete</button>
            </div>
          </div>
        </div>
      \`;
      document.getElementById("bdel-cancel").onclick = () => { modalRoot.innerHTML = ""; };
      document.getElementById("bdel-confirm").onclick = () => {
        const set = new Set(uris);
        state.files = state.files.filter(f => !set.has(f.uriString));
        state.recents = state.recents.filter(f => !set.has(f.uriString));
        state.selectedUris.clear();
        state.selectionMode = false;
        showSnackbar("Deleted " + count + " " + (count === 1 ? "file" : "files") + ".");
        modalRoot.innerHTML = "";
        render();
      };
    }

    function renderSelectionIcon(isSelected) {
      if (isSelected) {
        return '<svg width="22" height="22" viewBox="0 0 24 24" fill="var(--primary)"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-2 15-5-5 1.41-1.41L10 14.17l7.59-7.59L19 8l-9 9z"/></svg>';
      }
      return '<svg width="22" height="22" viewBox="0 0 24 24" fill="currentColor"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18c-4.42 0-8-3.58-8-8s3.58-8 8-8 8 3.58 8 8-3.58 8-8 8z"/></svg>';
    }

    function renderFileCards(files, emptyTitle, emptyBody) {
      if (files.length === 0) {
        return \`
          <div class="empty-state">
            <h3>\${emptyTitle}</h3>
            <p>\${emptyBody}</p>
          </div>
        \`;
      }
      if (state.layoutMode === "GRID") {
        return \`<div class="file-grid">\${files.map(f => {
          const isSelected = state.selectedUris.has(f.uriString);
          return \`
          <div class="file-card-grid" data-open="\${f.uriString}" style="\${isSelected ? 'border:2px solid var(--primary);' : ''}">
            <div class="card-top-row">
              <div class="category-badge">\${getCategoryIcon(f)}</div>
              \${state.selectionMode ? \`
                <button class="icon-btn" data-select="\${f.uriString}" title="Select file">
                  \${renderSelectionIcon(isSelected)}
                </button>
              \` : \`
                <button class="icon-btn" data-menu="\${f.uriString}" title="More actions">
                  <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor"><path d="M12 8c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zm0 2c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm0 6c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z"/></svg>
                </button>
              \`}
            </div>
            <div>
              <div class="file-title">\${f.displayName}</div>
              <div class="file-path">\${f.pathLabel}</div>
            </div>
            <div class="file-meta">
              <span>\${formatSize(f.size)}</span>
              <span>\${formatDate(f.lastModified)}</span>
            </div>
            \${!state.selectionMode && state.openMenuUri === f.uriString ? renderDropdown(f) : ""}
          </div>
        \`;
        }).join("")}</div>\`;
      } else {
        return \`<div class="file-list">\${files.map(f => {
          const isSelected = state.selectedUris.has(f.uriString);
          return \`
          <div class="file-card-list" data-open="\${f.uriString}" style="\${isSelected ? 'border:2px solid var(--primary);' : ''}">
            <div class="category-badge">\${getCategoryIcon(f)}</div>
            <div style="flex:1;min-width:0">
              <div class="file-title">\${f.displayName}</div>
              <div class="file-path">\${f.pathLabel}</div>
              <div class="file-path">\${formatSize(f.size)} | \${formatDate(f.lastModified)}</div>
            </div>
            \${state.selectionMode ? \`
              <button class="icon-btn" data-select="\${f.uriString}" title="Select file">
                \${renderSelectionIcon(isSelected)}
              </button>
            \` : \`
              <button class="icon-btn" data-menu="\${f.uriString}" title="More actions">
                <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor"><path d="M12 8c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zm0 2c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm0 6c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z"/></svg>
              </button>
            \`}
            \${!state.selectionMode && state.openMenuUri === f.uriString ? renderDropdown(f) : ""}
          </div>
        \`;
        }).join("")}</div>\`;
      }
    }

    function renderDropdown(f) {
      return \`
        <div class="dropdown-menu" onclick="event.stopPropagation()">
          <button class="dropdown-item" data-act="select" data-uri="\${f.uriString}">✓ Select</button>
          <button class="dropdown-item" data-act="bookmark" data-uri="\${f.uriString}">
            \${f.isBookmarked ? "★ Remove bookmark" : "☆ Add bookmark"}
          </button>
          <button class="dropdown-item" data-act="share" data-uri="\${f.uriString}">↗ Share</button>
          <button class="dropdown-item" data-act="rename" data-uri="\${f.uriString}">✎ Rename</button>
          <button class="dropdown-item" data-act="delete" data-uri="\${f.uriString}">🗑 Delete</button>
        </div>
      \`;
    }

    function drawPptxSlideToCanvas(canvas, slide) {
      const ctx = canvas.getContext("2d");
      const W = canvas.width;
      const H = canvas.height;
      const sw = slide.widthEmu || 12192000;
      const sh = slide.heightEmu || 6858000;
      const sx = (v) => (v / sw) * W;
      const sy = (v) => (v / sh) * H;

      ctx.clearRect(0, 0, W, H);

      const bg = slide.bg || { type: "solid", color: "#FFFFFF" };
      if (bg.type === "gradient" && bg.colors && bg.colors.length >= 2) {
        const grad = ctx.createLinearGradient(0, 0, W, H);
        bg.colors.forEach((c, i) => grad.addColorStop(i / (bg.colors.length - 1), c));
        ctx.fillStyle = grad;
        ctx.fillRect(0, 0, W, H);
      } else {
        ctx.fillStyle = bg.color || "#FFFFFF";
        ctx.fillRect(0, 0, W, H);
      }

      function drawWrappedParagraphs(paragraphs, x, y, w, h, vAnchor) {
        if (!paragraphs || !paragraphs.length) return;
        const padX = Math.max(4, Math.min(20, w * 0.03));
        const padY = Math.max(3, Math.min(16, h * 0.04));
        const innerW = Math.max(10, w - padX * 2);
        const lines = [];

        paragraphs.forEach(p => {
          const fullText = (p.runs || []).map(r => r.text).join("");
          const firstRun = (p.runs && p.runs[0]) || { fontSize: 18, bold: false, color: "#141414" };
          const fontPx = Math.max(7, Math.min(64, (firstRun.fontSize || 18) * (W / 960)));
          ctx.font = \`\${firstRun.bold ? "700" : "400"} \${fontPx}px Inter, sans-serif\`;

          fullText.split("\\n").forEach(rawLine => {
            const words = rawLine.split(/\\s+/).filter(Boolean);
            if (!words.length) return;
            let cur = "";
            words.forEach(word => {
              const cand = cur ? cur + " " + word : word;
              if (ctx.measureText(cand).width <= innerW) {
                cur = cand;
              } else {
                if (cur) lines.push({ text: cur, fontPx, bold: firstRun.bold, color: firstRun.color, align: p.align });
                cur = word;
              }
            });
            if (cur) lines.push({ text: cur, fontPx, bold: firstRun.bold, color: firstRun.color, align: p.align });
          });
        });

        const totalH = lines.reduce((acc, l) => acc + l.fontPx * 1.26, 0);
        let curY = y + padY;
        if (vAnchor === "center") curY = y + Math.max(padY, (h - totalH) / 2);
        else if (vAnchor === "bottom") curY = y + Math.max(padY, h - totalH - padY);

        ctx.save();
        ctx.beginPath();
        ctx.rect(x, y, w, h);
        ctx.clip();

        lines.forEach(l => {
          ctx.font = \`\${l.bold ? "700" : "400"} \${l.fontPx}px Inter, sans-serif\`;
          ctx.fillStyle = l.color || "#141414";
          ctx.textBaseline = "top";
          let tx = x + padX;
          if (l.align === "center") {
            ctx.textAlign = "center";
            tx = x + w / 2;
          } else if (l.align === "right") {
            ctx.textAlign = "right";
            tx = x + w - padX;
          } else {
            ctx.textAlign = "left";
          }
          ctx.fillText(l.text, tx, curY);
          curY += l.fontPx * 1.26;
        });
        ctx.restore();
      }

      (slide.elements || []).forEach(el => {
        const bx = sx(el.box.x || 0);
        const by = sy(el.box.y || 0);
        const bw = sx(el.box.width || 0);
        const bh = sy(el.box.height || 0);

        if (el.type === "image" && el.dataUrl) {
          const img = new Image();
          img.onload = () => { ctx.drawImage(img, bx, by, bw, bh); };
          img.src = el.dataUrl;
          return;
        }

        if (el.type === "connector") {
          ctx.save();
          const color = el.strokeColor || "#475569";
          const lw = Math.max(1.5, ((el.strokeWidthEmu || 18000) / sw) * W);
          ctx.strokeStyle = color;
          ctx.fillStyle = color;
          ctx.lineWidth = lw;
          const x2 = bx + bw;
          const y2 = by + bh;
          ctx.beginPath();
          ctx.moveTo(bx, by);
          ctx.lineTo(x2, y2);
          ctx.stroke();

          const angle = Math.atan2(y2 - by, x2 - bx);
          const headLen = Math.max(8, lw * 3.4);
          ctx.beginPath();
          ctx.moveTo(x2, y2);
          ctx.lineTo(x2 - headLen * Math.cos(angle - Math.PI / 6), y2 - headLen * Math.sin(angle - Math.PI / 6));
          ctx.lineTo(x2 - headLen * Math.cos(angle + Math.PI / 6), y2 - headLen * Math.sin(angle + Math.PI / 6));
          ctx.closePath();
          ctx.fill();
          ctx.restore();
          return;
        }

        if (el.type === "chart") {
          ctx.save();
          ctx.fillStyle = "#F8FAFC";
          ctx.strokeStyle = "#CBD5E1";
          ctx.lineWidth = Math.max(1, W / 640);
          ctx.beginPath();
          ctx.roundRect(bx, by, bw, bh, Math.min(bw, bh) * 0.03);
          ctx.fill();
          ctx.stroke();

          const titlePx = Math.max(10, Math.round(W * 0.018));
          ctx.font = \`700 \${titlePx}px Inter, sans-serif\`;
          ctx.fillStyle = "#0F172A";
          ctx.textAlign = "left";
          ctx.textBaseline = "top";
          ctx.fillText(el.chartTitle || "Chart", bx + bw * 0.04, by + bh * 0.05);

          const cats = el.categories || [];
          const vals = el.values || [];
          const colors = el.colors || ["#2563EB", "#0D9488", "#7C3AED", "#EA580C", "#16A34A"];
          const maxVal = Math.max(1, ...vals);
          const plotX = bx + bw * 0.08;
          const plotY = by + bh * 0.20;
          const plotW = bw * 0.86;
          const plotH = bh * 0.64;

          ctx.strokeStyle = "#E2E8F0";
          for (let g = 0; g <= 4; g++) {
            const gy = plotY + (plotH * g) / 4;
            ctx.beginPath();
            ctx.moveTo(plotX, gy);
            ctx.lineTo(plotX + plotW, gy);
            ctx.stroke();
          }

          const step = plotW / Math.max(1, vals.length);
          const barW = step * 0.52;
          const labelPx = Math.max(8, Math.round(W * 0.013));
          vals.forEach((v, idx) => {
            const bhPx = (v / maxVal) * plotH;
            const barX = plotX + idx * step + (step - barW) / 2;
            const barY = plotY + plotH - bhPx;
            ctx.fillStyle = colors[idx % colors.length];
            ctx.beginPath();
            ctx.roundRect(barX, barY, barW, bhPx, 4);
            ctx.fill();

            ctx.font = \`700 \${labelPx}px Inter, sans-serif\`;
            ctx.fillStyle = "#0F172A";
            ctx.textAlign = "center";
            ctx.fillText(String(v), barX + barW / 2, Math.max(by + 4, barY - labelPx * 1.3));

            if (cats[idx]) {
              ctx.font = \`500 \${labelPx}px Inter, sans-serif\`;
              ctx.fillStyle = "#475569";
              ctx.fillText(cats[idx], barX + barW / 2, plotY + plotH + labelPx * 0.5);
            }
          });
          ctx.restore();
          return;
        }

        if (el.type === "table") {
          const rows = el.rows || [];
          if (!rows.length) return;
          const colCount = Math.max(1, ...rows.map(r => (r.cells || []).length));
          const totalCW = (el.colWidths || []).reduce((a, b) => a + b, 0) || colCount;
          const totalRH = rows.reduce((a, r) => a + (r.height || 1), 0) || rows.length;
          let ry = by;
          rows.forEach((row, rIdx) => {
            const rh = bh * ((row.height || 1) / totalRH);
            let rx = bx;
            for (let cIdx = 0; cIdx < colCount; cIdx++) {
              const cw = el.colWidths && el.colWidths[cIdx] ? bw * (el.colWidths[cIdx] / totalCW) : bw / colCount;
              const cell = (row.cells || [])[cIdx];
              ctx.fillStyle = (cell && cell.fillColor) || (rIdx === 0 ? "#E2E8F0" : rIdx % 2 === 1 ? "#F8FAFC" : "#FFFFFF");
              ctx.fillRect(rx, ry, cw, rh);
              ctx.strokeStyle = "#CBD5E1";
              ctx.lineWidth = 1;
              ctx.strokeRect(rx, ry, cw, rh);
              if (cell && cell.paragraphs) {
                drawWrappedParagraphs(cell.paragraphs, rx + 4, ry + 2, cw - 8, rh - 4, "center");
              }
              rx += cw;
            }
            ry += rh;
          });
          return;
        }

        if (el.type === "shape") {
          ctx.save();
          ctx.beginPath();
          const p = (el.preset || "rect").toLowerCase();
          if (p === "ellipse" || p === "oval") {
            ctx.ellipse(bx + bw / 2, by + bh / 2, bw / 2, bh / 2, 0, 0, Math.PI * 2);
          } else if (p === "diamond") {
            ctx.moveTo(bx + bw / 2, by);
            ctx.lineTo(bx + bw, by + bh / 2);
            ctx.lineTo(bx + bw / 2, by + bh);
            ctx.lineTo(bx, by + bh / 2);
            ctx.closePath();
          } else if (p === "chevron" || p === "homeplate") {
            const notch = Math.min(bw * 0.22, bh * 0.45);
            ctx.moveTo(bx, by);
            ctx.lineTo(bx + bw - notch, by);
            ctx.lineTo(bx + bw, by + bh / 2);
            ctx.lineTo(bx + bw - notch, by + bh);
            ctx.lineTo(bx, by + bh);
            ctx.lineTo(bx + notch * 0.65, by + bh / 2);
            ctx.closePath();
          } else if (p.includes("roundrect")) {
            const r = Math.min(bw, bh) * 0.12;
            ctx.roundRect(bx, by, bw, bh, r);
          } else {
            ctx.rect(bx, by, bw, bh);
          }

          if (el.gradientColors && el.gradientColors.length >= 2) {
            const g = ctx.createLinearGradient(bx, by, bx + bw, by + bh);
            el.gradientColors.forEach((c, idx) => g.addColorStop(idx / (el.gradientColors.length - 1), c));
            ctx.fillStyle = g;
            ctx.fill();
          } else if (el.fillColor) {
            ctx.fillStyle = el.fillColor;
            ctx.fill();
          }

          if (el.strokeColor) {
            ctx.strokeStyle = el.strokeColor;
            ctx.lineWidth = Math.max(1.5, ((el.strokeWidthEmu || 12700) / sw) * W);
            ctx.stroke();
          }
          ctx.restore();

          if (el.paragraphs && el.paragraphs.length) {
            drawWrappedParagraphs(el.paragraphs, bx, by, bw, bh, el.verticalAnchor || "top");
          }
        }
      });
    }

    function renderViewer(file) {
      const isPpt = file.officeKind === "PRESENTATION" && file.pptxDeck && Array.isArray(file.pptxDeck.slides);
      if (isPpt) {
        const deck = file.pptxDeck;
        const slides = deck.slides || [];
        const total = slides.length;
        const curIdx = Math.max(0, Math.min(total - 1, state.activeSlide || 0));
        const aspect = (deck.widthEmu || 12192000) / (deck.heightEmu || 6858000);
        const cw = 1280;
        const ch = Math.round(cw / aspect);

        if (state.presentationMode === "PLAY") {
          const pct = total > 1 ? ((curIdx / (total - 1)) * 100).toFixed(1) : 100;
          return \`
            <div class="ppt-play-fullscreen" id="ppt-play-stage">
              <div class="ppt-top-hud">
                <div style="display:flex;align-items:center;gap:10px;">
                  <span class="ppt-hud-pill">Slide \${curIdx + 1} / \${total}</span>
                  <span style="font-size:13px;font-weight:600;color:#CBD5E1;max-width:260px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;">\${file.displayName}</span>
                </div>
                <div style="display:flex;align-items:center;gap:6px;flex-wrap:wrap;">
                  <button class="ppt-hud-btn" id="ppt-play-prev" \${curIdx <= 0 ? "disabled" : ""} title="Previous Slide (←)">◀ Prev</button>
                  <button class="ppt-hud-btn" id="ppt-play-next" \${curIdx >= total - 1 ? "disabled" : ""} title="Next Slide (→)">Next ▶</button>
                  <button class="ppt-hud-btn \${state.autoPlayActive ? 'active' : ''}" id="ppt-autoplay-toggle">
                    \${state.autoPlayActive ? "⏸ Auto-Play On" : "▶ Auto-Play"}
                  </button>
                  <button class="ppt-hud-btn \${state.laserActive ? 'active' : ''}" id="ppt-laser-toggle">🔴 Laser</button>
                  <button class="ppt-hud-btn" id="ppt-fullscreen-btn" title="Toggle Fullscreen">⛶</button>
                  <button class="ppt-hud-btn" id="ppt-play-exit" title="Exit Presentation (Esc)">✕ Exit</button>
                </div>
              </div>

              <div class="ppt-stage-center" id="ppt-laser-area" style="padding:18px 24px;">
                <canvas id="pptx-active-canvas" width="\${cw}" height="\${ch}" style="max-width:100%;max-height:100%;width:auto;height:auto;border-radius:6px;box-shadow:0 20px 60px rgba(0,0,0,0.65);background:#FFF;"></canvas>
                <div id="ppt-laser-dot" class="ppt-laser-dot"></div>
                <div id="ppt-play-left-zone" title="Click for Previous Slide" style="position:absolute;left:0;top:0;bottom:0;width:35%;cursor:pointer;z-index:15;"></div>
                <div id="ppt-play-right-zone" title="Click for Next Slide" style="position:absolute;right:0;top:0;bottom:0;width:65%;cursor:pointer;z-index:15;"></div>
              </div>

              <div style="padding:10px 20px 14px 20px;background:rgba(15,23,42,0.85);display:flex;align-items:center;gap:12px;z-index:20;">
                <span style="font-size:11px;color:#94A3B8;font-weight:600;">\${curIdx + 1} / \${total}</span>
                <div id="ppt-progress-scrub" style="flex:1;height:6px;background:rgba(255,255,255,0.18);border-radius:99px;cursor:pointer;position:relative;overflow:hidden;">
                  <div style="width:\${pct}%;height:100%;background:#38BDF8;border-radius:99px;transition:width 0.2s ease;"></div>
                </div>
                <span style="font-size:11px;color:#94A3B8;">Click left/right side of slide or use ← / → keys</span>
              </div>
            </div>
          \`;
        }

        const headerHtml = \`
          <div class="viewer-header">
            <button class="icon-btn" id="viewer-back" title="Back">
              <svg width="22" height="22" viewBox="0 0 24 24" fill="currentColor"><path d="M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z"/></svg>
            </button>
            <div class="viewer-title-col">
              <div class="viewer-title">\${file.displayName}</div>
              <div class="viewer-subtitle">Presentation • \${total} slides • \${formatSize(file.size)}</div>
            </div>
            <div class="ppt-mode-pill-group">
              <button class="ppt-mode-btn \${state.presentationMode === 'LIST' ? 'active' : ''}" id="ppt-mode-list">List</button>
              <button class="ppt-mode-btn \${state.presentationMode === 'VIEW' ? 'active' : ''}" id="ppt-mode-view">Slide</button>
            </div>
            <button class="btn-utility primary" id="viewer-play" style="padding:6px 12px;">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor"><path d="M8 5v14l11-7z"/></svg>
              Play
            </button>
            <button class="icon-btn" id="viewer-share" title="Share presentation">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor"><path d="M18 16.08c-.76 0-1.44.3-1.96.77L8.91 12.7c.05-.23.09-.46.09-.7s-.04-.47-.09-.7l7.05-4.11c.54.5 1.25.81 2.04.81 1.66 0 3-1.34 3-3s-1.34-3-3-3-3 1.34-3 3c0 .24.04.47.09.7L8.04 9.81C7.5 9.31 6.79 9 6 9c-1.66 0-3 1.34-3 3s1.34 3 3 3c.79 0 1.5-.31 2.04-.81l7.12 4.16c-.05.21-.08.43-.08.65 0 1.61 1.31 2.92 2.92 2.92s2.92-1.31 2.92-2.92-1.31-2.92-2.92-2.92z"/></svg>
            </button>
            <button class="icon-btn" id="viewer-bookmark" title="Bookmark">\${file.isBookmarked ? "★" : "☆"}</button>
          </div>
        \`;

        if (state.presentationMode === "VIEW") {
          return \`
            \${headerHtml}
            <div class="ppt-stage-overlay">
              <div class="ppt-top-hud">
                <span class="ppt-hud-pill">Slide \${curIdx + 1} / \${total}</span>
                <div style="display:flex;align-items:center;gap:6px;">
                  <button class="ppt-hud-btn" id="ppt-zoom-out">−</button>
                  <button class="ppt-hud-btn" id="ppt-zoom-reset">\${Math.round(state.slideZoom * 100)}%</button>
                  <button class="ppt-hud-btn" id="ppt-zoom-in">+</button>
                  <button class="ppt-hud-btn \${state.laserActive ? 'active' : ''}" id="ppt-laser-toggle">🔴 Laser</button>
                  <button class="ppt-hud-btn primary-play" id="ppt-view-play">▶ Play</button>
                  <button class="ppt-hud-btn" id="ppt-view-close">✕ List</button>
                </div>
              </div>

              <div class="ppt-stage-center" id="ppt-laser-area">
                <button class="ppt-nav-arrow" id="ppt-view-prev" style="left:12px;" \${curIdx <= 0 ? "disabled" : ""}>‹</button>
                <div id="ppt-zoom-container" style="display:flex;align-items:center;justify-content:center;max-width:100%;max-height:100%;transform:scale(\${state.slideZoom}) translate(\${state.slidePanX}px, \${state.slidePanY}px);transition:transform 0.12s ease;">
                  <canvas id="pptx-active-canvas" width="\${cw}" height="\${ch}" style="max-width:100%;max-height:calc(100vh - 290px);width:auto;height:auto;border-radius:8px;box-shadow:0 16px 44px rgba(0,0,0,0.55);background:#FFF;"></canvas>
                </div>
                <div id="ppt-laser-dot" class="ppt-laser-dot"></div>
                <button class="ppt-nav-arrow" id="ppt-view-next" style="right:12px;" \${curIdx >= total - 1 ? "disabled" : ""}>›</button>
              </div>

              <div class="ppt-filmstrip">
                \${slides.map((s, idx) => \`
                  <div class="ppt-thumb-item \${idx === curIdx ? 'active' : ''}" data-thumb-idx="\${idx}">
                    <canvas id="pptx-thumb-\${idx}" width="320" height="\${Math.round(320 / aspect)}" style="display:block;width:100%;height:auto;"></canvas>
                    <span class="ppt-thumb-num">\${idx + 1}</span>
                  </div>
                \`).join("")}
              </div>
            </div>
          \`;
        }

        const progressH = total > 1 ? Math.max(8, Math.round((curIdx / (total - 1)) * 84)) : 84;
        return \`
          \${headerHtml}
          <div class="viewer-content" id="ppt-list-scroll" style="background:#090B10;padding-right:68px;">
            \${slides.map((s, idx) => \`
              <div class="slide-canvas-wrap \${idx === curIdx ? 'active-slide-card' : ''}" id="pptx-card-\${idx}" data-slide-idx="\${idx}">
                <span class="slide-card-badge">Slide \${idx + 1} / \${total}</span>
                <canvas class="slide-canvas" id="pptx-canvas-\${idx}" width="\${cw}" height="\${ch}"></canvas>
                <span class="slide-card-open-hint">🔍 Click to inspect or zoom slide</span>
              </div>
            \`).join("")}
          </div>
          <div class="ppt-sidebar" title="Slide Navigation">
            <button class="icon-btn" id="ppt-side-prev" \${curIdx <= 0 ? "disabled" : ""}>
              <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor"><path d="M7.41 15.41 12 10.83l4.59 4.58L18 14l-6-6-6 6z"/></svg>
            </button>
            <span style="font-size:11px;font-weight:700;color:var(--on-surface);">\${curIdx + 1}/\${total}</span>
            <div class="ppt-sidebar-track">
              <div class="ppt-sidebar-fill" style="height:\${progressH}px;"></div>
            </div>
            <button class="icon-btn" id="ppt-side-next" \${curIdx >= total - 1 ? "disabled" : ""}>
              <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor"><path d="M7.41 8.59 12 13.17l4.59-4.58L18 10l-6 6-6-6z"/></svg>
            </button>
          </div>
        \`;
      }

      let bodyHtml = "";
      if (file.category === "TEXT") {
        bodyHtml = \`<div class="text-viewer-box">\${(file.textContent || "").replace(/</g, "&lt;")}</div>\`;
      } else if (file.officeKind === "SPREADSHEET") {
        const rows = file.tableRows || [];
        bodyHtml = \`
          <div class="sheet-container">
            <table class="sheet-table">
              <thead><tr>\${(rows[0] || []).map(c => \`<th>\${c}</th>\`).join("")}</tr></thead>
              <tbody>\${rows.slice(1).map(r => \`<tr>\${r.map(c => \`<td>\${c}</td>\`).join("")}</tr>\`).join("")}</tbody>
            </table>
          </div>
        \`;
      } else if (file.category === "PDF" && file.pdfBlobUrl) {
        bodyHtml = \`
          <div style="width:100%;height:100%;min-height:520px;display:flex;flex-direction:column;border-radius:10px;overflow:hidden;border:1px solid var(--panel-border);background:#FFFFFF;">
            <iframe src="\${file.pdfBlobUrl}" title="\${file.displayName}" style="width:100%;flex:1;min-height:520px;border:none;background:#FFFFFF;"></iframe>
          </div>
        \`;
      } else {
        const pages = file.pages || [{ title: file.displayName, body: "Document preview loaded.", sections: [] }];
        bodyHtml = pages.map((p, i) => \`
          <div class="doc-page">
            <h2>\${p.title}</h2>
            <p>\${p.body}</p>
            \${(p.sections || []).map(sec => \`
              <h3 style="margin-top:18px">\${sec.h}</h3>
              <p style="white-space:pre-line">\${sec.p}</p>
            \`).join("")}
            <div style="margin-top:24px;padding-top:12px;border-top:1px solid #E2E8F0;font-size:11px;color:#64748B;display:flex;justify-content:space-between">
              <span>\${file.displayName}</span>
              <span>Page \${i + 1} of \${pages.length}</span>
            </div>
          </div>
        \`).join("") + \`<div class="page-pill">Page 1 / \${pages.length} • 100%</div>\`;
      }

      return \`
        <div class="viewer-header">
          <button class="icon-btn" id="viewer-back" title="Back">
            <svg width="22" height="22" viewBox="0 0 24 24" fill="currentColor"><path d="M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z"/></svg>
          </button>
          <div class="viewer-title-col">
            <div class="viewer-title">\${file.displayName}</div>
            <div class="viewer-subtitle">\${file.category} • \${formatSize(file.size)}</div>
          </div>
          <button class="icon-btn" id="viewer-share" title="Share file">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor"><path d="M18 16.08c-.76 0-1.44.3-1.96.77L8.91 12.7c.05-.23.09-.46.09-.7s-.04-.47-.09-.7l7.05-4.11c.54.5 1.25.81 2.04.81 1.66 0 3-1.34 3-3s-1.34-3-3-3-3 1.34-3 3c0 .24.04.47.09.7L8.04 9.81C7.5 9.31 6.79 9 6 9c-1.66 0-3 1.34-3 3s1.34 3 3 3c.79 0 1.5-.31 2.04-.81l7.12 4.16c-.05.21-.08.43-.08.65 0 1.61 1.31 2.92 2.92 2.92s2.92-1.31 2.92-2.92-1.31-2.92-2.92-2.92z"/></svg>
          </button>
          <button class="icon-btn" id="viewer-bookmark" title="Bookmark">\${file.isBookmarked ? "★" : "☆"}</button>
        </div>
        <div class="viewer-content">\${bodyHtml}</div>
      \`;
    }

    function renderSelectionBar(visibleFiles) {
      const selectedInView = visibleFiles.filter(f => state.selectedUris.has(f.uriString));
      const count = selectedInView.length;
      const allSelected = visibleFiles.length > 0 && count === visibleFiles.length;
      return \`
        <div style="display:flex;align-items:center;gap:6px;padding:6px 10px;border-radius:22px;background:var(--panel-top-bg);border:1px solid var(--primary);box-shadow:var(--shadow);">
          <button class="icon-btn" id="sel-cancel" title="Cancel selection">✕</button>
          <span style="flex:1;font-size:14px;font-weight:600;">\${count} selected</span>
          <button class="icon-btn" id="sel-all" style="width:auto;padding:0 10px;border-radius:12px;font-size:12px;font-weight:600;color:\${allSelected ? 'var(--primary)' : 'var(--on-surface-variant)'}">
            \${allSelected ? "Deselect All" : "Select All"}
          </button>
          <button class="icon-btn" id="sel-share" title="Share selected files" \${count === 0 ? "disabled" : ""} style="color:\${count > 0 ? 'var(--primary)' : 'var(--on-surface-variant)'};opacity:\${count > 0 ? 1 : 0.4}">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor"><path d="M18 16.08c-.76 0-1.44.3-1.96.77L8.91 12.7c.05-.23.09-.46.09-.7s-.04-.47-.09-.7l7.05-4.11c.54.5 1.25.81 2.04.81 1.66 0 3-1.34 3-3s-1.34-3-3-3-3 1.34-3 3c0 .24.04.47.09.7L8.04 9.81C7.5 9.31 6.79 9 6 9c-1.66 0-3 1.34-3 3s1.34 3 3 3c.79 0 1.5-.31 2.04-.81l7.12 4.16c-.05.21-.08.43-.08.65 0 1.61 1.31 2.92 2.92 2.92s2.92-1.31 2.92-2.92-1.31-2.92-2.92-2.92z"/></svg>
          </button>
          <button class="icon-btn" id="sel-delete" title="Delete selected files" \${count === 0 ? "disabled" : ""} style="color:\${count > 0 ? '#ef4444' : 'var(--on-surface-variant)'};opacity:\${count > 0 ? 1 : 0.4}">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor"><path d="M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM8 9h8v10H8V9zm7.5-5-1-1h-5l-1 1H5v2h14V4h-3.5z"/></svg>
          </button>
        </div>
      \`;
    }

    function bindPresentationEvents(viewerEl, file) {
      const slides = (file.pptxDeck && file.pptxDeck.slides) || [];
      if (!slides.length) return;
      const total = slides.length;
      state.activeSlide = Math.max(0, Math.min(total - 1, state.activeSlide || 0));

      if (state.presentationMode === "LIST") {
        slides.forEach((s, idx) => {
          const c = document.getElementById("pptx-canvas-" + idx);
          if (c) drawPptxSlideToCanvas(c, s);
        });
      } else {
        const activeCanvas = document.getElementById("pptx-active-canvas");
        if (activeCanvas && slides[state.activeSlide]) {
          drawPptxSlideToCanvas(activeCanvas, slides[state.activeSlide]);
        }
        if (state.presentationMode === "VIEW") {
          slides.forEach((s, idx) => {
            const tc = document.getElementById("pptx-thumb-" + idx);
            if (tc) drawPptxSlideToCanvas(tc, s);
          });
        }
      }

      const goToSlide = (targetIdx, scrollCard) => {
        const clamped = Math.max(0, Math.min(total - 1, targetIdx));
        state.activeSlide = clamped;
        state.slideZoom = 1;
        state.slidePanX = 0;
        state.slidePanY = 0;
        render();
        if (scrollCard && state.presentationMode === "LIST") {
          const cardEl = document.getElementById("pptx-card-" + clamped);
          if (cardEl) cardEl.scrollIntoView({ behavior: "smooth", block: "center" });
        }
      };

      const modeListBtn = document.getElementById("ppt-mode-list");
      if (modeListBtn) modeListBtn.onclick = () => { stopAutoPlay(); state.presentationMode = "LIST"; render(); };
      const modeViewBtn = document.getElementById("ppt-mode-view");
      if (modeViewBtn) modeViewBtn.onclick = () => { stopAutoPlay(); state.presentationMode = "VIEW"; render(); };
      const playBtn = document.getElementById("viewer-play");
      if (playBtn) playBtn.onclick = () => { state.presentationMode = "PLAY"; render(); };
      const viewPlayBtn = document.getElementById("ppt-view-play");
      if (viewPlayBtn) viewPlayBtn.onclick = () => { state.presentationMode = "PLAY"; render(); };
      const viewCloseBtn = document.getElementById("ppt-view-close");
      if (viewCloseBtn) viewCloseBtn.onclick = () => { state.presentationMode = "LIST"; render(); };

      viewerEl.querySelectorAll("[data-slide-idx]").forEach(card => {
        card.onclick = () => {
          state.activeSlide = parseInt(card.dataset.slideIdx, 10) || 0;
          state.slideZoom = 1;
          state.presentationMode = "VIEW";
          render();
        };
      });
      const sidePrev = document.getElementById("ppt-side-prev");
      if (sidePrev) sidePrev.onclick = () => goToSlide(state.activeSlide - 1, true);
      const sideNext = document.getElementById("ppt-side-next");
      if (sideNext) sideNext.onclick = () => goToSlide(state.activeSlide + 1, true);

      const viewPrev = document.getElementById("ppt-view-prev");
      if (viewPrev) viewPrev.onclick = () => goToSlide(state.activeSlide - 1, false);
      const viewNext = document.getElementById("ppt-view-next");
      if (viewNext) viewNext.onclick = () => goToSlide(state.activeSlide + 1, false);

      const zoomIn = document.getElementById("ppt-zoom-in");
      if (zoomIn) zoomIn.onclick = () => { state.slideZoom = Math.min(3.0, +(state.slideZoom + 0.25).toFixed(2)); render(); };
      const zoomOut = document.getElementById("ppt-zoom-out");
      if (zoomOut) zoomOut.onclick = () => { state.slideZoom = Math.max(0.5, +(state.slideZoom - 0.25).toFixed(2)); render(); };
      const zoomReset = document.getElementById("ppt-zoom-reset");
      if (zoomReset) zoomReset.onclick = () => { state.slideZoom = 1; state.slidePanX = 0; state.slidePanY = 0; render(); };

      viewerEl.querySelectorAll("[data-thumb-idx]").forEach(thumb => {
        thumb.onclick = () => goToSlide(parseInt(thumb.dataset.thumbIdx, 10) || 0, false);
      });

      const playPrev = document.getElementById("ppt-play-prev");
      if (playPrev) playPrev.onclick = () => goToSlide(state.activeSlide - 1, false);
      const playNext = document.getElementById("ppt-play-next");
      if (playNext) playNext.onclick = () => goToSlide(state.activeSlide + 1, false);

      const leftZone = document.getElementById("ppt-play-left-zone");
      if (leftZone) leftZone.onclick = () => { if (state.activeSlide > 0) goToSlide(state.activeSlide - 1, false); else showSnackbar("First slide"); };
      const rightZone = document.getElementById("ppt-play-right-zone");
      if (rightZone) rightZone.onclick = () => { if (state.activeSlide < total - 1) goToSlide(state.activeSlide + 1, false); else showSnackbar("End of presentation"); };

      const autoPlayBtn = document.getElementById("ppt-autoplay-toggle");
      if (autoPlayBtn) autoPlayBtn.onclick = () => toggleAutoPlay();

      const laserBtn = document.getElementById("ppt-laser-toggle");
      if (laserBtn) laserBtn.onclick = () => { state.laserActive = !state.laserActive; render(); };

      const laserArea = document.getElementById("ppt-laser-area");
      const laserDot = document.getElementById("ppt-laser-dot");
      if (laserArea && laserDot && state.laserActive) {
        laserArea.onmousemove = (e) => {
          const rect = laserArea.getBoundingClientRect();
          laserDot.style.display = "block";
          laserDot.style.left = (e.clientX - rect.left) + "px";
          laserDot.style.top = (e.clientY - rect.top) + "px";
        };
        laserArea.onmouseleave = () => { laserDot.style.display = "none"; };
      }

      const fsBtn = document.getElementById("ppt-fullscreen-btn");
      if (fsBtn) {
        fsBtn.onclick = () => {
          const stage = document.getElementById("ppt-play-stage") || document.documentElement;
          if (!document.fullscreenElement && stage.requestFullscreen) {
            stage.requestFullscreen().catch(() => showSnackbar("Fullscreen presentation active in viewport."));
          } else if (document.fullscreenElement && document.exitFullscreen) {
            document.exitFullscreen().catch(() => {});
          }
        };
      }

      const playExit = document.getElementById("ppt-play-exit");
      if (playExit) {
        playExit.onclick = () => {
          stopAutoPlay();
          if (document.fullscreenElement && document.exitFullscreen) document.exitFullscreen().catch(() => {});
          state.presentationMode = "LIST";
          render();
        };
      }

      const scrub = document.getElementById("ppt-progress-scrub");
      if (scrub) {
        scrub.onclick = (e) => {
          const rect = scrub.getBoundingClientRect();
          const ratio = Math.max(0, Math.min(1, (e.clientX - rect.left) / Math.max(1, rect.width)));
          goToSlide(Math.round(ratio * (total - 1)), false);
        };
      }
    }

    function render() {
      applyTheme();
      const tabTitles = { FILES: "Files", RECENTS: "Recents", BOOKMARKS: "Bookmarks", SETTINGS: "Settings" };
      document.getElementById("top-bar-tab-label").textContent = tabTitles[state.tab] || "Files";

      document.querySelectorAll(".nav-item").forEach(btn => {
        btn.classList.toggle("active", btn.dataset.tab === state.tab);
      });

      const viewerEl = document.getElementById("viewer-screen");
      if (state.activeViewerFile) {
        viewerEl.style.display = "flex";
        viewerEl.innerHTML = renderViewer(state.activeViewerFile);

        if (state.activeViewerFile.officeKind === "PRESENTATION" && state.activeViewerFile.pptxDeck) {
          bindPresentationEvents(viewerEl, state.activeViewerFile);
        }

        const backBtn = document.getElementById("viewer-back");
        if (backBtn) backBtn.onclick = () => { stopAutoPlay(); state.activeViewerFile = null; state.presentationMode = "LIST"; render(); };
        const shareBtn = document.getElementById("viewer-share");
        if (shareBtn) shareBtn.onclick = () => {
          if (window.AndroidBridge && window.AndroidBridge.shareText) {
            window.AndroidBridge.shareText(state.activeViewerFile.displayName, "Shared from AllVie v4.0: " + state.activeViewerFile.displayName);
          } else {
            showSnackbar("Sharing " + state.activeViewerFile.displayName + "...");
          }
        };
        const bmBtn = document.getElementById("viewer-bookmark");
        if (bmBtn) bmBtn.onclick = () => toggleBookmark(state.activeViewerFile.uriString);
        return;
      } else {
        viewerEl.style.display = "none";
      }

      const body = document.getElementById("screen-body");
      if (state.tab === "SETTINGS") {
        const isNativeAndroid = Boolean(window.AndroidBridge && window.AndroidBridge.getSdkInt);
        const runtimeLabel = isNativeAndroid
          ? ("Android " + window.AndroidBridge.getRelease() + " (API " + window.AndroidBridge.getSdkInt() + ") • " + window.AndroidBridge.getDeviceName())
          : "Android 8.0 (API 26) — Android 16 (API 36) Universal Engine";

        body.innerHTML = \`
          <div style="padding: 8px 4px; display:flex; flex-direction:column; gap:16px;">
            <div>
              <div style="font-size:16px;font-weight:600;margin-bottom:8px;">Theme</div>
              <div class="chips-row">
                \${["SYSTEM", "LIGHT", "DARK"].map(m => \`
                  <button class="filter-chip \${state.themeMode === m ? 'selected' : ''}" data-theme-mode="\${m}">
                    \${m.charAt(0) + m.slice(1).toLowerCase()}
                  </button>
                \`).join("")}
              </div>
            </div>

            <div style="padding:16px;border-radius:20px;background:linear-gradient(180deg, var(--panel-top-bg), var(--panel-bot-bg));border:1px solid var(--panel-border);box-shadow:var(--shadow);display:flex;flex-direction:column;gap:12px;">
              <div style="display:flex;align-items:center;justify-content:space-between;flex-wrap:wrap;gap:8px;">
                <div>
                  <div style="font-size:15px;font-weight:700;">Android 8.0 – Android 16 Compatibility</div>
                  <div style="font-size:12px;color:var(--on-surface-variant);margin-top:2px;">\${runtimeLabel}</div>
                </div>
                <span class="brand-badge">minSdk 26 • targetSdk 35</span>
              </div>
              <div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(210px,1fr));gap:10px;font-size:12.5px;">
                <div style="padding:10px 12px;border-radius:14px;background:var(--chip-bg);border:1px solid var(--panel-border);">
                  <div style="font-weight:700;color:var(--primary);margin-bottom:3px;">Android 8.0–8.1 Oreo (API 26–27)</div>
                  <div style="color:var(--on-surface-variant);line-height:1.45;">Adaptive Launcher Icons (v26), Legacy Storage + SAF picker, hardware Canvas2D slide renderer.</div>
                </div>
                <div style="padding:10px 12px;border-radius:14px;background:var(--chip-bg);border:1px solid var(--panel-border);">
                  <div style="font-weight:700;color:var(--primary);margin-bottom:3px;">Android 9 Pie & 10 Q (API 28–29)</div>
                  <div style="color:var(--on-surface-variant);line-height:1.45;">Legacy External Storage bridge, display cutout support, system dark theme sync.</div>
                </div>
                <div style="padding:10px 12px;border-radius:14px;background:var(--chip-bg);border:1px solid var(--panel-border);">
                  <div style="font-weight:700;color:var(--primary);margin-bottom:3px;">Android 11–12L (API 30–32)</div>
                  <div style="color:var(--on-surface-variant);line-height:1.45;">Scoped Storage MediaStore scanner, ACTION_SEND_MULTIPLE batch share & batch delete.</div>
                </div>
                <div style="padding:10px 12px;border-radius:14px;background:var(--chip-bg);border:1px solid var(--panel-border);">
                  <div style="font-weight:700;color:var(--primary);margin-bottom:3px;">Android 13–16 (API 33–36)</div>
                  <div style="color:var(--on-surface-variant);line-height:1.45;">Monochrome themed icon, zero-permission SAF document opener, edge-to-edge layout.</div>
                </div>
              </div>
            </div>

            <div style="padding:14px 16px;border-radius:18px;background:var(--chip-bg);border:1px solid var(--panel-border);display:flex;align-items:center;justify-content:space-between;flex-wrap:wrap;gap:10px;">
              <div>
                <div style="font-size:14px;font-weight:600;">AllVie v4.0 — Universal Offline Reader</div>
                <div style="font-size:12px;color:var(--on-surface-variant);">Supports PDF, PPTX,PPT, DOCX, DOC, XLSX, XLS, CSV, XML, TXT, MD, JSON, LOG</div>
              </div>
              <a class="btn-utility primary" href="/download/app-debug.apk" download="AllVie-v4.0-debug.apk">Download APK (v4.0)</a>
            </div>
          </div>
        \`;
        body.querySelectorAll("[data-theme-mode]").forEach(btn => {
          btn.onclick = () => { state.themeMode = btn.dataset.themeMode; render(); };
        });
        return;
      }

      let visibleFiles = [];
      let emptyTitle = "No files found";
      let emptyBody = "Try another filter or wait for the next scan.";

      if (state.tab === "FILES") {
        visibleFiles = state.files.filter(f => {
          const matchCat = state.selectedFilter === "ALL" || f.category === state.selectedFilter;
          const q = state.searchQuery.trim().toLowerCase();
          const matchQ = !q || f.displayName.toLowerCase().includes(q) || f.pathLabel.toLowerCase().includes(q);
          return matchCat && matchQ;
        });
      } else if (state.tab === "RECENTS") {
        const q = state.searchQuery.trim().toLowerCase();
        visibleFiles = state.recents.filter(f => !q || f.displayName.toLowerCase().includes(q) || f.pathLabel.toLowerCase().includes(q));
        emptyTitle = "Nothing opened yet";
        emptyBody = "Open a supported file from the Files tab and it will appear here.";
      } else if (state.tab === "BOOKMARKS") {
        const q = state.searchQuery.trim().toLowerCase();
        visibleFiles = state.files.filter(f => f.isBookmarked && (!q || f.displayName.toLowerCase().includes(q) || f.pathLabel.toLowerCase().includes(q)));
        emptyTitle = "No bookmarks yet";
        emptyBody = "Bookmark a file from the Files or Recents tabs to see it here.";
      }

      const categories = [
        { id: "ALL", label: "All" },
        { id: "PDF", label: "PDF" },
        { id: "TEXT", label: "Text" },
        { id: "OFFICE", label: "Office" }
      ];

      body.innerHTML = \`
        <div class="search-bar">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor"><path d="M15.5 14h-.79l-.28-.27A6.471 6.471 0 0 0 16 9.5 6.5 6.5 0 1 0 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z"/></svg>
          <input class="search-input" id="search-input" placeholder="Search files" value="\${state.searchQuery.replace(/"/g, '&quot;')}" />
          \${state.tab === "FILES" ? \`
            <button class="icon-btn" id="rescan-btn" title="Rescan files">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor"><path d="M17.65 6.35A7.958 7.958 0 0 0 12 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08A5.99 5.99 0 0 1 12 18c-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z"/></svg>
            </button>
          \` : (visibleFiles.length > 0 && !state.selectionMode ? \`
            <button class="icon-btn" id="enter-select-btn" title="Select multiple files">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor"><path d="M22 7h-9v2h9V7zm0 8h-9v2h9v-2zM5.54 11 2 7.46l1.41-1.41 2.12 2.12 4.24-4.24 1.41 1.41L5.54 11zm0 8L2 15.46l1.41-1.41 2.12 2.12 4.24-4.24 1.41 1.41L5.54 19z"/></svg>
            </button>
          \` : "")}
        </div>

        \${state.tab === "FILES" ? \`
          <div class="chips-row">
            \${categories.map(c => \`
              <button class="filter-chip \${state.selectedFilter === c.id ? 'selected' : ''}" data-cat="\${c.id}">\${c.label}</button>
            \`).join("")}
          </div>
          \${!state.selectionMode ? \`
            <div class="toolbar-row">
              <span class="items-count">\${visibleFiles.length} items</span>
              <div style="display:flex;align-items:center;gap:4px;">
                \${visibleFiles.length > 0 ? \`
                  <button class="icon-btn" id="enter-select-btn" title="Select multiple files">
                    <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor"><path d="M22 7h-9v2h9V7zm0 8h-9v2h9v-2zM5.54 11 2 7.46l1.41-1.41 2.12 2.12 4.24-4.24 1.41 1.41L5.54 11zm0 8L2 15.46l1.41-1.41 2.12 2.12 4.24-4.24 1.41 1.41L5.54 19z"/></svg>
                  </button>
                \` : ""}
                <button class="icon-btn" id="layout-toggle" title="Toggle layout">
                  \${state.layoutMode === "GRID"
                    ? '<svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor"><path d="M3 13h8V3H3v10zm0 8h8v-6H3v6zm10 0h8V11h-8v10zm0-18v6h8V3h-8z"/></svg>'
                    : '<svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor"><path d="M3 14h4v-4H3v4zm0 5h4v-4H3v4zM3 9h4V5H3v4zm5 5h13v-4H8v4zm0 5h13v-4H8v4zM8 5v4h13V5H8z"/></svg>'}
                </button>
              </div>
            </div>
          \` : ""}
        \` : ""}

        \${state.selectionMode ? renderSelectionBar(visibleFiles) : ""}

        <div class="file-collection-wrap">
          \${renderFileCards(visibleFiles, emptyTitle, emptyBody)}
        </div>
      \`;

      const searchInput = document.getElementById("search-input");
      if (searchInput) {
        searchInput.oninput = (e) => {
          state.searchQuery = e.target.value;
          const pos = e.target.selectionStart;
          render();
          const next = document.getElementById("search-input");
          if (next) { next.focus(); next.setSelectionRange(pos, pos); }
        };
      }

      const rescanBtn = document.getElementById("rescan-btn");
      if (rescanBtn) rescanBtn.onclick = () => {
        if (window.AndroidBridge && window.AndroidBridge.requestStorageScan) {
          window.AndroidBridge.requestStorageScan();
        }
        showSnackbar("Scanned device storage (" + state.files.length + " supported files).");
      };

      const enterSelectBtn = document.getElementById("enter-select-btn");
      if (enterSelectBtn) enterSelectBtn.onclick = () => { state.selectionMode = true; state.selectedUris.clear(); render(); };

      const selCancel = document.getElementById("sel-cancel");
      if (selCancel) selCancel.onclick = () => { state.selectionMode = false; state.selectedUris.clear(); render(); };

      const selAll = document.getElementById("sel-all");
      if (selAll) {
        selAll.onclick = () => {
          const allSelected = visibleFiles.length > 0 && visibleFiles.every(f => state.selectedUris.has(f.uriString));
          if (allSelected) visibleFiles.forEach(f => state.selectedUris.delete(f.uriString));
          else visibleFiles.forEach(f => state.selectedUris.add(f.uriString));
          render();
        };
      }

      const selShare = document.getElementById("sel-share");
      if (selShare) {
        selShare.onclick = () => {
          const selected = visibleFiles.filter(f => state.selectedUris.has(f.uriString));
          if (!selected.length) return;
          if (window.AndroidBridge && window.AndroidBridge.shareText) {
            window.AndroidBridge.shareText("Share " + selected.length + " files", selected.map(s => s.displayName).join(", "));
          } else {
            showSnackbar("Sharing " + selected.length + " " + (selected.length === 1 ? "file" : "files") + "...");
          }
          state.selectionMode = false;
          state.selectedUris.clear();
          render();
        };
      }

      const selDelete = document.getElementById("sel-delete");
      if (selDelete) {
        selDelete.onclick = () => {
          const uris = visibleFiles.filter(f => state.selectedUris.has(f.uriString)).map(f => f.uriString);
          showBatchDeleteModal(uris);
        };
      }

      const layoutToggle = document.getElementById("layout-toggle");
      if (layoutToggle) layoutToggle.onclick = () => { state.layoutMode = state.layoutMode === "GRID" ? "LIST" : "GRID"; render(); };

      body.querySelectorAll("[data-cat]").forEach(btn => {
        btn.onclick = () => { state.selectedFilter = btn.dataset.cat; render(); };
      });

      body.querySelectorAll("[data-open]").forEach(card => {
        card.onclick = () => {
          const uri = card.dataset.open;
          if (state.selectionMode) { toggleSelectUri(uri); return; }
          const f = state.files.find(x => x.uriString === uri);
          if (f) openFile(f);
        };
      });

      body.querySelectorAll("[data-select]").forEach(btn => {
        btn.onclick = (e) => { e.stopPropagation(); toggleSelectUri(btn.dataset.select); };
      });

      body.querySelectorAll("[data-menu]").forEach(btn => {
        btn.onclick = (e) => {
          e.stopPropagation();
          const uri = btn.dataset.menu;
          state.openMenuUri = state.openMenuUri === uri ? null : uri;
          render();
        };
      });

      body.querySelectorAll("[data-act]").forEach(btn => {
        btn.onclick = (e) => {
          e.stopPropagation();
          const uri = btn.dataset.uri;
          const f = state.files.find(x => x.uriString === uri);
          if (!f) return;
          const act = btn.dataset.act;
          if (act === "select") toggleSelectUri(uri);
          else if (act === "bookmark") toggleBookmark(uri);
          else if (act === "share") {
            state.openMenuUri = null;
            if (window.AndroidBridge && window.AndroidBridge.shareText) {
              window.AndroidBridge.shareText(f.displayName, "Shared from AllVie v4.0: " + f.displayName);
            } else {
              showSnackbar("Sharing " + f.displayName + "...");
            }
            render();
          } else if (act === "rename") showRenameModal(f);
          else if (act === "delete") showDeleteModal(f);
        };
      });
    }

    document.querySelectorAll(".nav-item").forEach(btn => {
      btn.onclick = () => {
        state.tab = btn.dataset.tab;
        state.activeViewerFile = null;
        state.openMenuUri = null;
        state.selectionMode = false;
        state.selectedUris.clear();
        render();
      };
    });

    // --- Offline Client-Side ZIP & OpenXML Parser for Android 8.0-16 WebView + Browser ---
    async function inflateRawBytes(rawUint8) {
      if (typeof DecompressionStream !== "undefined") {
        const ds = new DecompressionStream("deflate-raw");
        const writer = ds.writable.getWriter();
        writer.write(rawUint8);
        writer.close();
        const outBuf = await new Response(ds.readable).arrayBuffer();
        return new Uint8Array(outBuf);
      }
      if (window.AndroidBridge && window.AndroidBridge.inflateRawBase64) {
        let bin = "";
        for (let i = 0; i < rawUint8.length; i++) bin += String.fromCharCode(rawUint8[i]);
        const outB64 = window.AndroidBridge.inflateRawBase64(btoa(bin));
        if (outB64) {
          const decBin = atob(outB64);
          const out = new Uint8Array(decBin.length);
          for (let i = 0; i < decBin.length; i++) out[i] = decBin.charCodeAt(i);
          return out;
        }
      }
      return rawUint8;
    }

    async function readZipArrayBufferClient(arrayBuffer) {
      const view = new DataView(arrayBuffer);
      const bytes = new Uint8Array(arrayBuffer);
      const entries = {};
      let eocd = -1;
      for (let i = bytes.length - 22; i >= Math.max(0, bytes.length - 65558); i--) {
        if (view.getUint32(i, true) === 0x06054b50) { eocd = i; break; }
      }
      if (eocd === -1) return entries;
      const total = view.getUint16(eocd + 10, true);
      let ptr = view.getUint32(eocd + 16, true);
      const decoder = new TextDecoder("utf-8");

      for (let i = 0; i < total && ptr + 46 <= bytes.length; i++) {
        if (view.getUint32(ptr, true) !== 0x02014b50) break;
        const method = view.getUint16(ptr + 10, true);
        const compSize = view.getUint32(ptr + 20, true);
        const nameLen = view.getUint16(ptr + 28, true);
        const extraLen = view.getUint16(ptr + 30, true);
        const commentLen = view.getUint16(ptr + 32, true);
        const localOff = view.getUint32(ptr + 42, true);
        const name = decoder.decode(bytes.subarray(ptr + 46, ptr + 46 + nameLen));
        ptr += 46 + nameLen + extraLen + commentLen;
        if (localOff + 30 > bytes.length || view.getUint32(localOff, true) !== 0x04034b50) continue;
        const lNameLen = view.getUint16(localOff + 26, true);
        const lExtraLen = view.getUint16(localOff + 28, true);
        const dataStart = localOff + 30 + lNameLen + lExtraLen;
        if (dataStart + compSize > bytes.length) continue;
        const slice = bytes.subarray(dataStart, dataStart + compSize);
        try {
          if (method === 0) entries[name] = slice;
          else if (method === 8) entries[name] = await inflateRawBytes(slice);
        } catch (_) {}
      }
      return entries;
    }

    function decodeXmlClient(s) {
      return (s || "")
        .replace(/&amp;/g, "&")
        .replace(/&lt;/g, "<")
        .replace(/&gt;/g, ">")
        .replace(/&quot;/g, '"')
        .replace(/&apos;/g, "'");
    }

    function parsePptxEntriesClient(entries) {
      const dec = new TextDecoder("utf-8");
      const getStr = (p) => entries[p] ? dec.decode(entries[p]) : "";
      const presXml = getStr("ppt/presentation.xml");
      let widthEmu = 12192000, heightEmu = 6858000;
      const szM = presXml.match(/<sldSz[^>]*cx=["'](\\d+)["'][^>]*cy=["'](\\d+)["']/i);
      if (szM) {
        widthEmu = parseInt(szM[1], 10) || widthEmu;
        heightEmu = parseInt(szM[2], 10) || heightEmu;
      }
      const slideKeys = Object.keys(entries)
        .filter(k => /^ppt\\/slides\\/slide\\d+\\.xml$/.test(k))
        .sort((a, b) => parseInt(a.match(/(\\d+)/)[1], 10) - parseInt(b.match(/(\\d+)/)[1], 10));

      const slides = slideKeys.map((key, idx) => {
        const xml = getStr(key);
        const elements = [];
        const spMatches = [...xml.matchAll(/<(?:\\w+:)?sp\\b[^>]*>([\\s\\S]*?)<\\/(?:\\w+:)?sp>/g)];
        spMatches.forEach((m, sIdx) => {
          const inner = m[1];
          const offM = inner.match(/<(?:\\w+:)?off\\b[^>]*x=["'](-?\\d+)["'][^>]*y=["'](-?\\d+)["']/);
          const extM = inner.match(/<(?:\\w+:)?ext\\b[^>]*cx=["'](\\d+)["'][^>]*cy=["'](\\d+)["']/);
          const box = (offM && extM)
            ? { x: parseInt(offM[1], 10), y: parseInt(offM[2], 10), width: parseInt(extM[1], 10), height: parseInt(extM[2], 10) }
            : { x: Math.round(widthEmu * 0.07), y: Math.round(heightEmu * (sIdx === 0 ? 0.08 : 0.26)), width: Math.round(widthEmu * 0.86), height: Math.round(heightEmu * (sIdx === 0 ? 0.16 : 0.62)) };
          const pMatches = [...inner.matchAll(/<(?:\\w+:)?p\\b[^>]*>([\\s\\S]*?)<\\/(?:\\w+:)?p>/g)];
          const paragraphs = [];
          pMatches.forEach(pm => {
            const texts = [...pm[1].matchAll(/<(?:\\w+:)?t\\b[^>]*>([\\s\\S]*?)<\\/(?:\\w+:)?t>/g)].map(t => decodeXmlClient(t[1])).join("");
            if (texts.trim()) {
              paragraphs.push({
                align: "left",
                runs: [{ text: texts, fontSize: sIdx === 0 ? 28 : 18, bold: sIdx === 0, color: "#0F172A" }]
              });
            }
          });
          if (paragraphs.length) {
            elements.push({ type: "shape", preset: "rect", box, paragraphs, verticalAnchor: "top" });
          }
        });
        return { index: idx + 1, widthEmu, heightEmu, bg: { type: "solid", color: "#FFFFFF" }, elements };
      });
      return { widthEmu, heightEmu, slides };
    }

    function parseDocxEntriesClient(entries, fileName) {
      const dec = new TextDecoder("utf-8");
      const xml = entries["word/document.xml"] ? dec.decode(entries["word/document.xml"]) : "";
      if (!xml) return [{ title: fileName, body: "Loaded Word document.", sections: [] }];
      const paras = [...xml.matchAll(/<(?:\\w+:)?p\\b[^>]*>([\\s\\S]*?)<\\/(?:\\w+:)?p>/g)]
        .map(m => [...m[1].matchAll(/<(?:\\w+:)?t\\b[^>]*>([\\s\\S]*?)<\\/(?:\\w+:)?t>/g)].map(t => decodeXmlClient(t[1])).join(""))
        .filter(t => t.trim().length > 0);
      if (!paras.length) return [{ title: fileName, body: "Document contains no text paragraphs.", sections: [] }];
      const pages = [];
      for (let i = 0; i < paras.length; i += 8) {
        const chunk = paras.slice(i, i + 8);
        pages.push({
          title: i === 0 ? (chunk[0] || fileName) : (fileName + " — Page " + (pages.length + 1)),
          body: i === 0 ? (chunk[1] || "") : (chunk[0] || ""),
          sections: chunk.slice(i === 0 ? 2 : 1).map((p, idx) => ({ h: "Paragraph " + (i + idx + 1), p }))
        });
      }
      return pages;
    }

    function parseXlsxEntriesClient(entries) {
      const dec = new TextDecoder("utf-8");
      const sharedXml = entries["xl/sharedStrings.xml"] ? dec.decode(entries["xl/sharedStrings.xml"]) : "";
      const shared = [...sharedXml.matchAll(/<(?:\\w+:)?si\\b[^>]*>([\\s\\S]*?)<\\/(?:\\w+:)?si>/g)]
        .map(m => [...m[1].matchAll(/<(?:\\w+:)?t\\b[^>]*>([\\s\\S]*?)<\\/(?:\\w+:)?t>/g)].map(t => decodeXmlClient(t[1])).join(""));
      const sheetKey = Object.keys(entries).find(k => /^xl\\/worksheets\\/sheet\\d+\\.xml$/.test(k));
      if (!sheetKey) return [["Sheet1"], ["No worksheet rows found"]];
      const sheetXml = dec.decode(entries[sheetKey]);
      const rows = [...sheetXml.matchAll(/<(?:\\w+:)?row\\b[^>]*>([\\s\\S]*?)<\\/(?:\\w+:)?row>/g)].slice(0, 60).map(rm => {
        return [...rm[1].matchAll(/<(?:\\w+:)?c\\b([^>]*)>([\\s\\S]*?)<\\/(?:\\w+:)?c>/g)].map(cm => {
          const tAttr = (cm[1].match(/\\bt=["']([^"']+)["']/) || [])[1] || "";
          const vMatch = cm[2].match(/<(?:\\w+:)?v\\b[^>]*>([\\s\\S]*?)<\\/(?:\\w+:)?v>/);
          const rawVal = vMatch ? decodeXmlClient(vMatch[1]) : "";
          if (tAttr === "s") return shared[parseInt(rawVal, 10)] || rawVal;
          return rawVal;
        });
      }).filter(r => r.length > 0);
      return rows.length ? rows : [["Sheet1"], ["Empty spreadsheet"]];
    }

    window.__onAndroidExternalFile = async (fileName, mimeType, fileSize, base64Data) => {
      try {
        const binStr = atob(base64Data);
        const bytes = new Uint8Array(binStr.length);
        for (let i = 0; i < binStr.length; i++) bytes[i] = binStr.charCodeAt(i);
        const blob = new Blob([bytes], { type: mimeType || "application/octet-stream" });
        const fileObj = new File([blob], fileName || "External_Document", { type: mimeType || "application/octet-stream", lastModified: Date.now() });
        await handleImportedFileObject(fileObj, "Android Intent / External File");
      } catch (err) {
        showSnackbar("Failed to open external file: " + fileName);
      }
    };

    async function handleImportedFileObject(file, sourcePathLabel) {
      const ext = (file.name.split(".").pop() || "").toLowerCase();
      let category = "TEXT";
      let officeKind = "DOCUMENT";
      if (ext === "pdf") category = "PDF";
      else if (["doc", "docx", "xls", "xlsx", "ppt", "pptx"].includes(ext)) {
        category = "OFFICE";
        if (["xls", "xlsx"].includes(ext)) officeKind = "SPREADSHEET";
        else if (["ppt", "pptx"].includes(ext)) officeKind = "PRESENTATION";
      } else if (ext === "csv") {
        officeKind = "SPREADSHEET";
      }

      if (ext === "pptx") {
        try {
          const buf = await file.arrayBuffer();
          let pptxDeck = null;
          try {
            const res = await fetch("/api/parse-pptx", {
              method: "POST",
              headers: { "Content-Type": "application/octet-stream" },
              body: buf
            });
            if (res.ok) pptxDeck = await res.json();
          } catch (_) {}
          if (!pptxDeck || !pptxDeck.slides || !pptxDeck.slides.length) {
            const entries = await readZipArrayBufferClient(buf);
            pptxDeck = parsePptxEntriesClient(entries);
          }
          if (pptxDeck && pptxDeck.slides && pptxDeck.slides.length) {
            const newItem = {
              uriString: "content://com.allvie.app/uploads/" + Date.now() + "_" + file.name,
              displayName: file.name,
              mimeType: file.type || "application/vnd.openxmlformats-officedocument.presentationml.presentation",
              category: "OFFICE",
              officeKind: "PRESENTATION",
              size: file.size,
              lastModified: file.lastModified || Date.now(),
              pathLabel: sourcePathLabel || "Internal Storage / Uploaded",
              isBookmarked: false,
              pptxDeck
            };
            state.files.unshift(newItem);
            openFile(newItem);
            showSnackbar("Rendered " + pptxDeck.slides.length + " slides from " + file.name);
            return;
          }
        } catch (_) {}
      }

      if (ext === "docx" || ext === "xlsx") {
        try {
          const buf = await file.arrayBuffer();
          const entries = await readZipArrayBufferClient(buf);
          const newItem = {
            uriString: "content://com.allvie.app/uploads/" + Date.now() + "_" + file.name,
            displayName: file.name,
            mimeType: file.type || "application/octet-stream",
            category: "OFFICE",
            officeKind,
            size: file.size,
            lastModified: file.lastModified || Date.now(),
            pathLabel: sourcePathLabel || "Internal Storage / Uploaded",
            isBookmarked: false,
            pages: ext === "docx" ? parseDocxEntriesClient(entries, file.name) : undefined,
            tableRows: ext === "xlsx" ? parseXlsxEntriesClient(entries) : undefined
          };
          state.files.unshift(newItem);
          openFile(newItem);
          showSnackbar("Opened " + file.name);
          return;
        } catch (_) {}
      }

      if (ext === "pdf") {
        const pdfBlobUrl = URL.createObjectURL(file);
        const newItem = {
          uriString: "content://com.allvie.app/uploads/" + Date.now() + "_" + file.name,
          displayName: file.name,
          mimeType: "application/pdf",
          category: "PDF",
          officeKind: "DOCUMENT",
          size: file.size,
          lastModified: file.lastModified || Date.now(),
          pathLabel: sourcePathLabel || "Internal Storage / Uploaded",
          isBookmarked: false,
          pdfBlobUrl
        };
        state.files.unshift(newItem);
        openFile(newItem);
        showSnackbar("Opened PDF " + file.name);
        return;
      }

      const reader = new FileReader();
      reader.onload = () => {
        const text = typeof reader.result === "string" ? reader.result : "";
        const csvRows = ext === "csv"
          ? text.split(/\\r?\\n/).filter(Boolean).slice(0, 100).map(line => line.split(",").map(c => c.trim()))
          : undefined;
        const newItem = {
          uriString: "content://com.allvie.app/uploads/" + Date.now() + "_" + file.name,
          displayName: file.name,
          mimeType: file.type || "application/octet-stream",
          category,
          officeKind,
          size: file.size,
          lastModified: file.lastModified || Date.now(),
          pathLabel: sourcePathLabel || "Internal Storage / Uploaded",
          isBookmarked: false,
          textContent: category === "TEXT" && ext !== "csv" ? text : undefined,
          pages: [{ title: file.name, body: text ? text.slice(0, 2000) : "Loaded local file (" + formatSize(file.size) + ").", sections: [] }],
          tableRows: csvRows || (officeKind === "SPREADSHEET" ? [["Column A", "Column B", "Size"], [file.name, ext.toUpperCase(), formatSize(file.size)]] : undefined)
        };
        state.files.unshift(newItem);
        openFile(newItem);
        showSnackbar("Opened " + file.name);
      };
      reader.readAsText(file);
    }

    document.getElementById("file-upload-input").onchange = async (e) => {
      const file = e.target.files && e.target.files[0];
      if (!file) return;
      await handleImportedFileObject(file, "Internal Storage / Uploaded");
    };

    window.onclick = () => {
      if (state.openMenuUri) {
        state.openMenuUri = null;
        render();
      }
    };

    render();
  </script>
</body>
</html>`;

const server = http.createServer((req, res) => {
  const url = (req.url || '/').split('?')[0];

  if (req.method === 'POST' && url === '/api/parse-pptx') {
    const chunks = [];
    req.on('data', chunk => chunks.push(chunk));
    req.on('end', () => {
      try {
        const buf = Buffer.concat(chunks);
        const deck = parseSlideShowBuffer(buf);
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify(deck));
      } catch (err) {
        res.writeHead(400, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: String(err) }));
      }
    });
    return;
  }

  if (url === '/download/app-debug.apk') {
    const apkPath = getApkPath();
    if (fs.existsSync(apkPath)) {
      const stat = fs.statSync(apkPath);
      res.writeHead(200, {
        'Content-Type': 'application/vnd.android.package-archive',
        'Content-Length': stat.size,
        'Content-Disposition': 'attachment; filename="AllVie-v4.0-debug.apk"'
      });
      fs.createReadStream(apkPath).pipe(res);
      return;
    } else {
      res.writeHead(404, { 'Content-Type': 'text/plain' });
      res.end('APK is still building or not found.');
      return;
    }
  }

  if (url === '/health') {
    const apkPath = getApkPath();
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ status: 'ok', version: '4.0', apkBuilt: fs.existsSync(apkPath), apkPath }));
    return;
  }

  res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
  res.end(HTML_PAGE);
});

server.listen(PORT, '0.0.0.0', () => {
  console.log('AllVie v4.0 preview server listening on http://0.0.0.0:' + PORT);
});
