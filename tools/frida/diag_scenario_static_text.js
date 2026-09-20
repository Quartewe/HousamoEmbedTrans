'use strict';

// Generate a standalone script with prepare.py before loading (see README.md).
//
// Goal:
//   Verify whether text can be read directly from:
//     AdvDataManager.FindScenarioData(label)
//       -> AdvScenarioData.scenarioLabels
//       -> AdvScenarioLabelData.Next / PageDataList
//       -> AdvScenarioPageData.CommandList
//
// This script does not hook InitFromPageData.

const RVA = {
  DataManagerFindScenarioData: runtimeNumber('RVA.RVA_FindScenarioData'),
};

const OFF = {
  str_len: runtimeNumber('Layout.Il2CppString.Length'),
  str_chars: runtimeNumber('Layout.Il2CppString.Chars'),

  array_len: runtimeNumber('Layout.Il2CppArray.Length'),
  array_first: runtimeNumber('Layout.Il2CppArray.FirstElement'),

  list_items: runtimeNumber('Layout.Il2CppList.Items'),
  list_size: runtimeNumber('Layout.Il2CppList.Size'),

  dict_entries: runtimeNumber('Layout.Il2CppDictionary.Entries'),
  dict_count: runtimeNumber('Layout.Il2CppDictionary.Count'),

  dictEntry_hashCode: runtimeNumber('Layout.DictionaryEntry.HashCode'),
  dictEntry_key: runtimeNumber('Layout.DictionaryEntry.Key'),
  dictEntry_value: runtimeNumber('Layout.DictionaryEntry.Value'),
  dictEntry_size: runtimeNumber('Layout.DictionaryEntry.Size'),

  scenarioData_name: runtimeNumber('Layout.AdvScenarioData.Name'),
  scenarioData_scenarioLabels: runtimeNumber('Layout.AdvScenarioData.ScenarioLabels'),

  label_pageDataList: runtimeNumber('Layout.ScenarioLabelData.PageDataList'),
  label_scenarioLabel: runtimeNumber('Layout.ScenarioLabelData.ScenarioLabel'),
  label_next: runtimeNumber('Layout.ScenarioLabelData.Next'),

  page_commandList: runtimeNumber('Layout.AdvScenarioPageData.CommandList'),
  page_pageNo: runtimeNumber('Layout.AdvScenarioPageData.PageNo'),

  cmd_row: runtimeNumber('Layout.AdvCommand.RowData'),
  cmd_type: runtimeNumber('Layout.AdvCommand.Type'),

  character_info: runtimeNumber('Layout.AdvCommandCharacter.CharacterInfo'),
  charinfo_nameText: runtimeNumber('Layout.AdvCommandCharacter.NameText'),

  selection_jumpLabel: runtimeNumber('Layout.AdvCommandSelection.JumpLabel'),
  jump_jumpLabel: runtimeNumber('Layout.AdvCommandJump.JumpLabel'),

  row_strings: runtimeNumber('Layout.StringGridRow.Strings'),
};

const TEXT_COL = {
  raw: runtimeNumber('Layout.TextColumns.Raw'),
  zhCn: runtimeNumber('Layout.TextColumns.ZhCn'),
};

const MAX_TEXT_LINES = 1600;
const MAX_LABELS = 256;
const MAX_PAGES_PER_LABEL = 512;
const MAX_COMMANDS_PER_PAGE = 4096;

let gBase = null;
let gSeq = 0;
let gTextLines = 0;
const dumpedScenarios = new Set();

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

function shortText(text, max = 120) {
  return text.replace(/\s+/g, ' ').slice(0, max);
}

function readList(list) {
  const size = ri(list, OFF.list_size);
  const items = rp(list, OFF.list_items);
  if (size < 0 || size > 8192 || !valid(items)) {
    return { ok: false, size, items: NULL };
  }
  return { ok: true, size, items };
}

function listElem(list, index) {
  if (!list.ok || index < 0 || index >= list.size) return NULL;
  return rp(list.items, OFF.array_first + index * Process.pointerSize);
}

function readRowColumn(row, column) {
  const strings = rp(row, OFF.row_strings);
  if (!valid(strings)) return '';

  const len = ri(strings, OFF.array_len);
  if (column < 0 || len <= column || len > 256) return '';

  return readStr(rp(strings, OFF.array_first + column * Process.pointerSize));
}

function labelName(labelData) {
  return readStr(rp(labelData, OFF.label_scenarioLabel));
}

function enumerateScenarioLabels(scenarioData) {
  const labels = [];
  const dict = rp(scenarioData, OFF.scenarioData_scenarioLabels);
  if (!valid(dict)) return labels;

  const count = ri(dict, OFF.dict_count);
  const entries = rp(dict, OFF.dict_entries);
  const capacity = ri(entries, OFF.array_len);
  if (count <= 0 || count > 8192 || capacity <= 0 || capacity > 8192 || !valid(entries)) {
    return labels;
  }

  const limit = Math.min(count, capacity);
  const first = entries.add(OFF.array_first);

  for (let i = 0; i < limit; i++) {
    const entry = first.add(i * OFF.dictEntry_size);
    const hashCode = ri(entry, OFF.dictEntry_hashCode);
    if (hashCode < 0) continue;

    const key = readStr(rp(entry, OFF.dictEntry_key));
    const labelData = rp(entry, OFF.dictEntry_value);
    const valueName = labelName(labelData);
    const label = valueName || key;

    if (!label || !valid(labelData)) continue;

    const nextData = rp(labelData, OFF.label_next);
    const nextName = labelName(nextData);

    labels.push({
      key,
      label,
      nextName,
      labelData,
    });
  }

  return labels;
}

function buildLabelOrder(labels, scenarioName, requestedLabel) {
  const byName = new Map();
  for (const item of labels) {
    byName.set(item.label, item);
    if (item.key) byName.set(item.key, item);
  }

  let current = byName.get(scenarioName) || byName.get(requestedLabel) || labels[0];
  const order = [];
  const seenPtrs = new Set();

  while (current && order.length < MAX_LABELS) {
    const ptrKey = current.labelData.toString();
    if (seenPtrs.has(ptrKey)) {
      console.log(`[WARN] Next loop at label="${current.label}"`);
      break;
    }

    seenPtrs.add(ptrKey);
    order.push(current);

    if (!current.nextName) break;
    current = byName.get(current.nextName);
    if (!current) {
      console.log(`[WARN] Next target missing: "${order[order.length - 1].nextName}"`);
      break;
    }
  }

  const unvisited = labels.filter((item) => !seenPtrs.has(item.labelData.toString()));
  unvisited.sort((a, b) => a.label.localeCompare(b.label));
  return { order, unvisited };
}

function dumpTextLine(kind, label, pageNo, cmdIndex, speaker, text, extra = '') {
  if (!text || gTextLines >= MAX_TEXT_LINES) return;
  gTextLines++;
  console.log(
    `[${kind} ${gTextLines}] label="${label}" page=${pageNo} cmd=${cmdIndex}` +
    ` speaker="${speaker}" text="${shortText(text)}"${extra}`
  );
}

function dumpPageText(label, pageData) {
  const pageNo = ri(pageData, OFF.page_pageNo);
  const commandList = readList(rp(pageData, OFF.page_commandList));
  if (!commandList.ok || commandList.size > MAX_COMMANDS_PER_PAGE) {
    return { pages: 1, text: 0, selection: 0, jump: 0 };
  }

  let textCount = 0;
  let selectionCount = 0;
  let jumpCount = 0;
  let speaker = '';

  for (let i = 0; i < commandList.size; i++) {
    const cmd = listElem(commandList, i);
    if (!valid(cmd)) continue;

    const type = readStr(rp(cmd, OFF.cmd_type));
    const row = rp(cmd, OFF.cmd_row);

    if (type === 'Character') {
      const info = rp(cmd, OFF.character_info);
      speaker = readStr(rp(info, OFF.charinfo_nameText));
      continue;
    }

    if (type === 'CharacterOff') {
      speaker = '';
      continue;
    }

    if (type === 'Text') {
      const raw = readRowColumn(row, TEXT_COL.raw);
      const zh = readRowColumn(row, TEXT_COL.zhCn);
      textCount++;
      dumpTextLine('TEXT', label, pageNo, i, speaker, raw, zh ? ' zhCn=HAS' : '');
      continue;
    }

    if (type === 'Selection') {
      const option = readRowColumn(row, TEXT_COL.raw);
      const jump = readStr(rp(cmd, OFF.selection_jumpLabel));
      selectionCount++;
      dumpTextLine('SEL', label, pageNo, i, 'mc', option, ` -> "${jump}"`);
      continue;
    }

    if (type === 'Jump' || type === 'JumpRandom' || type === 'JumpSubroutine') {
      jumpCount++;
    }
  }

  return { pages: 1, text: textCount, selection: selectionCount, jump: jumpCount };
}

function dumpLabelText(item) {
  const pageList = readList(rp(item.labelData, OFF.label_pageDataList));
  if (!pageList.ok || pageList.size > MAX_PAGES_PER_LABEL) {
    console.log(`[LABEL] label="${item.label}" pageList invalid size=${pageList.size}`);
    return { labels: 1, pages: 0, text: 0, selection: 0, jump: 0 };
  }

  let pages = 0;
  let text = 0;
  let selection = 0;
  let jump = 0;

  for (let i = 0; i < pageList.size; i++) {
    const pageData = listElem(pageList, i);
    if (!valid(pageData)) continue;

    const stats = dumpPageText(item.label, pageData);
    pages += stats.pages;
    text += stats.text;
    selection += stats.selection;
    jump += stats.jump;
  }

  return { labels: 1, pages, text, selection, jump };
}

function dumpScenarioStaticText(scenarioData, requestedLabel) {
  if (!valid(scenarioData)) {
    console.log(`[FindScenarioData] requested="${requestedLabel}" scenarioData=NULL`);
    return;
  }

  const key = scenarioData.toString();
  if (dumpedScenarios.has(key)) {
    console.log(`[FindScenarioData] scenarioData=${scenarioData} already dumped`);
    return;
  }
  dumpedScenarios.add(key);

  gTextLines = 0;

  const scenarioName = readStr(rp(scenarioData, OFF.scenarioData_name));
  const labels = enumerateScenarioLabels(scenarioData);
  const { order, unvisited } = buildLabelOrder(labels, scenarioName, requestedLabel);

  console.log(
    `[SCENARIO] requested="${requestedLabel}" scenario="${scenarioName}"` +
    ` scenarioData=${scenarioData} labels=${labels.length}` +
    ` order=${order.length} unvisited=${unvisited.length}`
  );

  let totalLabels = 0;
  let totalPages = 0;
  let totalText = 0;
  let totalSelection = 0;
  let totalJump = 0;

  for (let i = 0; i < order.length; i++) {
    const item = order[i];
    console.log(`[ORDER ${i + 1}/${order.length}] label="${item.label}" next="${item.nextName}"`);
    const stats = dumpLabelText(item);
    totalLabels += stats.labels;
    totalPages += stats.pages;
    totalText += stats.text;
    totalSelection += stats.selection;
    totalJump += stats.jump;
  }

  console.log(
    `[SUMMARY] labels=${totalLabels}/${labels.length} pages=${totalPages}` +
    ` text=${totalText} selection=${totalSelection} jump=${totalJump}` +
    ` printed=${gTextLines}/${MAX_TEXT_LINES} unvisited=${unvisited.length}`
  );

  if (unvisited.length > 0) {
    console.log(`[UNVISITED] ${unvisited.map((item) => item.label).join(', ')}`);
  }
}

function hookRva(name, rva, callbacks) {
  const target = gBase.add(rva);
  Interceptor.attach(target, callbacks);
  console.log(`[+] hook ${name} @ ${target} rva=0x${rva.toString(16)}`);
}

function install() {
  const module = Process.findModuleByName('libil2cpp.so');
  if (!module) {
    setTimeout(install, 500);
    return;
  }

  gBase = module.base;
  console.log(`[+] libil2cpp base=${gBase}`);

  hookRva('AdvDataManager.FindScenarioData', RVA.DataManagerFindScenarioData, {
    onEnter(args) {
      this.requestedLabel = readStr(args[1]);
      console.log(`-> #${++gSeq} FindScenarioData requested="${this.requestedLabel}"`);
    },
    onLeave(retval) {
      dumpScenarioStaticText(retval, this.requestedLabel);
    },
  });
}

console.log('[*] diag_scenario_static_text waiting libil2cpp...');
install();
