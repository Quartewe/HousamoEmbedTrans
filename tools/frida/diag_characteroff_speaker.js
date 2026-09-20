'use strict';

// Generate a standalone script with prepare.py before loading (see README.md).
//
// Goal:
//   Compare two speaker policies for every Text command:
//     keepOff  = CharacterOff does not clear speaker
//     clearOff = CharacterOff clears speaker
//   Inspect command ordering only; this does not validate displayed speaker text.

const RVA = {
  InitText: runtimeNumber('RVA.RVA_InitText'),
};

const OFF = {
  page_commandList: runtimeNumber('Layout.AdvScenarioPageData.CommandList'),
  page_scenarioLabelData: runtimeNumber('Layout.AdvScenarioPageData.ScenarioLabelData'),
  page_pageNo: runtimeNumber('Layout.AdvScenarioPageData.PageNo'),

  label_scenarioLabel: runtimeNumber('Layout.ScenarioLabelData.ScenarioLabel'),

  list_items: runtimeNumber('Layout.Il2CppList.Items'),
  list_size: runtimeNumber('Layout.Il2CppList.Size'),

  array_len: runtimeNumber('Layout.Il2CppArray.Length'),
  array_first: runtimeNumber('Layout.Il2CppArray.FirstElement'),

  cmd_row: runtimeNumber('Layout.AdvCommand.RowData'),
  cmd_type: runtimeNumber('Layout.AdvCommand.Type'),


  character_info: runtimeNumber('Layout.AdvCommandCharacter.CharacterInfo'),
  charinfo_nameText: runtimeNumber('Layout.AdvCommandCharacter.NameText'),

  row_strings: runtimeNumber('Layout.StringGridRow.Strings'),

  str_len: runtimeNumber('Layout.Il2CppString.Length'),
  str_chars: runtimeNumber('Layout.Il2CppString.Chars'),
};

const COL = {
  raw: runtimeNumber('Layout.TextColumns.Raw'),
};

const PRINT_ALL_TEXT = true;
const PRINT_CONTEXT_ON_DIFF = true;
const CONTEXT_RADIUS = 4;

let gBase = null;
let gTextCount = 0;

const seenTextInit = new Set();

function valid(p) {
  return p !== null && !p.isNull() && p.compare(ptr('0x100000')) > 0;
}

function rp(base, off) {
  try {
    if (!valid(base)) return NULL;
    const p = base.add(off).readPointer();
    return valid(p) ? p : NULL;
  } catch (_) {
    return NULL;
  }
}

function ri(base, off) {
  try {
    if (!valid(base)) return -1;
    return base.add(off).readS32();
  } catch (_) {
    return -1;
  }
}

function readStr(str) {
  try {
    if (!valid(str)) return '';
    const len = str.add(OFF.str_len).readS32();
    if (len <= 0 || len > 8192) return '';
    return str.add(OFF.str_chars).readUtf16String(len) || '';
  } catch (_) {
    return '';
  }
}

function readList(list) {
  const size = ri(list, OFF.list_size);
  const items = rp(list, OFF.list_items);
  if (size < 0 || size > 4096 || !valid(items)) {
    return { ok: false, size, items: NULL };
  }
  return { ok: true, size, items };
}

function readArrayElem(arrayObj, index) {
  return rp(arrayObj, OFF.array_first + index * Process.pointerSize);
}

function trunc(s, n = 90) {
  if (!s) return '';
  const oneLine = String(s).replace(/\s+/g, ' ');
  return oneLine.length > n ? oneLine.slice(0, n) + '...' : oneLine;
}

function compactName(name) {
  return name && name.length > 0 ? name : '(none)';
}

function rowString(row, column) {
  const arr = rp(row, OFF.row_strings);
  if (!valid(arr)) return '';

  const len = ri(arr, OFF.array_len);
  if (len <= column || len > 4096) return '';

  return readStr(readArrayElem(arr, column));
}

function readCmdType(cmd) {
  return readStr(rp(cmd, OFF.cmd_type));
}

function readCharacterName(cmd) {
  const info = rp(cmd, OFF.character_info);
  return readStr(rp(info, OFF.charinfo_nameText));
}

function pageInfo(pageData) {
  const pageNo = ri(pageData, OFF.page_pageNo);
  const labelData = rp(pageData, OFF.page_scenarioLabelData);
  const label = readStr(rp(labelData, OFF.label_scenarioLabel));
  const commandList = rp(pageData, OFF.page_commandList);
  const list = readList(commandList);
  return { pageNo, label, commandList, list };
}

function commandAt(list, index) {
  if (!list.ok || index < 0 || index >= list.size) return NULL;
  return readArrayElem(list.items, index);
}

function samePtr(a, b) {
  return valid(a) && valid(b) && a.toString() === b.toString();
}

function findCommandIndex(list, targetCmd) {
  if (!list.ok || !valid(targetCmd)) return -1;

  for (let i = 0; i < list.size; i++) {
    const cmd = commandAt(list, i);
    if (samePtr(cmd, targetCmd)) return i;
  }

  return -1;
}

function commandBrief(list, index) {
  const cmd = commandAt(list, index);
  if (!valid(cmd)) return `#${index} <invalid>`;

  const type = readCmdType(cmd);
  if (type === 'Character') {
    return `#${index} Character name="${readCharacterName(cmd)}"`;
  }
  if (type === 'CharacterOff') {
    return `#${index} CharacterOff`;
  }
  if (type === 'Text') {
    const text = rowString(rp(cmd, OFF.cmd_row), COL.raw);
    return `#${index} Text "${trunc(text, 50)}"`;
  }
  return `#${index} ${type}`;
}

function scanSpeakerPolicies(pageData, textIndex) {
  const page = pageInfo(pageData);
  let keepOff = '';
  let clearOff = '';
  const speakerEvents = [];

  if (!page.list.ok) {
    return { page, keepOff, clearOff, speakerEvents };
  }

  const limit = Math.min(textIndex, page.list.size - 1);
  for (let i = 0; i <= limit; i++) {
    const cmd = commandAt(page.list, i);
    if (!valid(cmd)) continue;

    const type = readCmdType(cmd);
    if (type === 'Character') {
      const name = readCharacterName(cmd);
      keepOff = name;
      clearOff = name;
      speakerEvents.push(`#${i}:Character="${name}"`);
    } else if (type === 'CharacterOff') {
      clearOff = '';
      speakerEvents.push(`#${i}:CharacterOff keep="${compactName(keepOff)}" clear="${compactName(clearOff)}"`);
    }
  }

  return { page, keepOff, clearOff, speakerEvents };
}

function contextAround(page, textIndex) {
  if (!page.list.ok) return [];

  const start = Math.max(0, textIndex - CONTEXT_RADIUS);
  const end = Math.min(page.list.size - 1, textIndex + CONTEXT_RADIUS);
  const lines = [];
  for (let i = start; i <= end; i++) {
    lines.push('    ' + commandBrief(page.list, i));
  }
  return lines;
}

function onInitText(args) {
  const self = args[0];
  const pageData = args[1];
  if (!valid(self) || !valid(pageData)) return;

  const type = readCmdType(self);
  if (type !== 'Text') return;

  const row = rp(self, OFF.cmd_row);
  const raw = rowString(row, COL.raw);
  if (!raw) return;

  const page = pageInfo(pageData);
  const commandIndex = findCommandIndex(page.list, self);
  if (commandIndex < 0) {
    console.log(`[SKIP] Text command not found in CommandList: ${self}`);
    return;
  }
  const scanIndex = commandIndex;

  const key = `${pageData}:${self}:${scanIndex}:${raw}`;
  if (seenTextInit.has(key)) return;
  seenTextInit.add(key);

  const scan = scanSpeakerPolicies(pageData, scanIndex);
  const diff = scan.keepOff !== scan.clearOff;
  gTextCount++;

  if (PRINT_ALL_TEXT || diff) {
    console.log(
      `[TextInit${diff ? ' DIFF' : ''}] #${gTextCount} ` +
      `page=${scan.page.pageNo} cmdIdx=${commandIndex} label="${scan.page.label}" ` +
      `keepOff="${compactName(scan.keepOff)}" clearOff="${compactName(scan.clearOff)}" ` +
      `text="${trunc(raw)}"`
    );
  }

  if (diff) {
    console.log(`  events: ${scan.speakerEvents.join(' -> ')}`);
    if (PRINT_CONTEXT_ON_DIFF) {
      for (const line of contextAround(scan.page, scanIndex)) {
        console.log(line);
      }
    }
  }
}

function hook(name, rva, callback) {
  const target = gBase.add(rva);
  Interceptor.attach(target, { onEnter: callback });
  console.log(`[+] hook ${name} @ ${target}`);
}

function install() {
  const module = Process.findModuleByName('libil2cpp.so');
  if (!module) {
    setTimeout(install, 500);
    return;
  }

  gBase = module.base;
  console.log(`[+] libil2cpp base=${gBase}`);

  hook('AdvCommandText.InitFromPageData', RVA.InitText, onInitText);

  setInterval(() => {
    console.log(
      `[SUMMARY] textInit=${gTextCount}`
    );
  }, 5000);
}

console.log('[*] diag_characteroff_speaker waiting libil2cpp...');
install();
