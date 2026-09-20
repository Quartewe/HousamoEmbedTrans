'use strict';

// Generate a standalone script with prepare.py before loading (see README.md).
//
// Data path:
//   AdvDataManager.FindScenarioData(label)
//     -> AdvScenarioData.scenarioLabels
//     -> AdvScenarioLabelData.PageDataList
//     -> AdvScenarioPageData.CommandList
//
// This script does not depend on InitFromPageData and
// therefore includes branches that were not selected during the current run.
// This is an investigative graph simulation, not the current native parser.
// It never mutates game objects.

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
  selection_jumpLabel: runtimeNumber('Layout.AdvCommandSelection.JumpLabel'),
  jump_jumpLabel: runtimeNumber('Layout.AdvCommandJump.JumpLabel'),

  row_strings: runtimeNumber('Layout.StringGridRow.Strings'),
};

const COL = {
  condition: runtimeNumber('Layout.AdvCommandJump.ConditionColumn'),
  raw: runtimeNumber('Layout.TextColumns.Raw'),
};

// Leave empty to inspect every scenario returned by FindScenarioData.
const SCENARIO_FILTER = HET_OPTIONS.sceneFilter;

const MAX_LABELS = 512;
const MAX_PAGES_PER_LABEL = 8192;
const MAX_COMMANDS_PER_PAGE = 4096;
const VIRTUAL_EXIT = '<exit>';

let gBase = null;
let gSeq = 0;
const dumpedRequests = new Set();

function valid(p) {
  return p !== null && !p.isNull() && p.compare(ptr('0x100000')) > 0;
}

function rp(base, off) {
  try {
    if (!valid(base)) return NULL;
    const value = base.add(off).readPointer();
    return valid(value) ? value : NULL;
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
    const length = str.add(OFF.str_len).readS32();
    if (length <= 0 || length > 8192) return '';
    return str.add(OFF.str_chars).readUtf16String(length) || '';
  } catch (_) {
    return '';
  }
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

  const length = ri(strings, OFF.array_len);
  if (column < 0 || length <= column || length > 256) return '';

  return readStr(rp(strings, OFF.array_first + column * Process.pointerSize));
}

function labelName(labelData) {
  return readStr(rp(labelData, OFF.label_scenarioLabel));
}

function shortText(text, maxLength = 80) {
  return (text || '').replace(/\s+/g, ' ').slice(0, maxLength);
}

function formatOrder(order) {
  return `${order.labelIndex}:${order.pageNo}:${order.cmdIndex}:${order.subIndex}`;
}

function formatLabels(labels) {
  if (!labels || labels.length === 0) return '(empty)';
  return labels.map((item) => typeof item === 'string' ? item : item.label).join(' -> ');
}

function enumerateScenarioLabels(scenarioData) {
  const labels = [];
  const dict = rp(scenarioData, OFF.scenarioData_scenarioLabels);
  if (!valid(dict)) return labels;

  const count = ri(dict, OFF.dict_count);
  const entries = rp(dict, OFF.dict_entries);
  const capacity = ri(entries, OFF.array_len);
  if (count <= 0 || count > 8192 || capacity <= 0 || capacity > 8192 || !valid(entries)) {
    console.log(`[ERROR] invalid scenarioLabels count=${count} capacity=${capacity}`);
    return labels;
  }

  const limit = Math.min(count, capacity);
  const first = entries.add(OFF.array_first);

  for (let i = 0; i < limit; i++) {
    const entry = first.add(i * OFF.dictEntry_size);
    if (ri(entry, OFF.dictEntry_hashCode) < 0) continue;

    const key = readStr(rp(entry, OFF.dictEntry_key));
    const labelData = rp(entry, OFF.dictEntry_value);
    if (!valid(labelData)) continue;

    const valueName = labelName(labelData);
    const label = valueName || key;
    if (!label) continue;

    labels.push({
      key,
      label,
      nextName: labelName(rp(labelData, OFF.label_next)),
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

  let start = scenarioName;
  if (!byName.has(start)) start = requestedLabel;
  if (!byName.has(start)) start = labels.length > 0 ? labels[0].label : '';

  const ordered = [];
  const visited = new Set();
  let current = start;

  while (current && ordered.length < labels.length) {
    if (visited.has(current)) {
      console.log(`[WARN] Next loop at label="${current}"`);
      break;
    }

    const item = byName.get(current);
    if (!item) {
      console.log(`[WARN] missing Next target label="${current}"`);
      break;
    }

    visited.add(current);
    ordered.push(item);
    current = item.nextName;
  }

  const unvisited = labels
    .filter((item) => !visited.has(item.label))
    .sort((a, b) => a.label.localeCompare(b.label));

  return { ordered: ordered.concat(unvisited), unvisited, start };
}

function flushChoice(pending, items) {
  if (pending !== null && pending.branches.length > 0) {
    items.push(pending);
  }
  return null;
}

function parseLabelBlock(item, labelIndex) {
  const block = {
    index: labelIndex,
    label: item.label,
    nextLabel: item.nextName,
    labelData: item.labelData,
    jumps: [],
    items: [],
    continuation: '',
    hasTerminalJump: false,
    consumed: false,
    consumedBy: '',
  };

  const pages = readList(rp(item.labelData, OFF.label_pageDataList));
  if (!pages.ok || pages.size > MAX_PAGES_PER_LABEL) {
    console.log(`[ERROR] invalid PageDataList label="${item.label}" size=${pages.size}`);
    block.invalid = true;
    return block;
  }

  for (let pageIndex = 0; pageIndex < pages.size; pageIndex++) {
    const pageData = listElem(pages, pageIndex);
    if (!valid(pageData)) continue;

    const pageNo = ri(pageData, OFF.page_pageNo);
    const commands = readList(rp(pageData, OFF.page_commandList));
    if (!commands.ok || commands.size > MAX_COMMANDS_PER_PAGE) {
      console.log(
        `[ERROR] invalid CommandList label="${item.label}" pageIndex=${pageIndex}` +
        ` pageNo=${pageNo} size=${commands.size}`
      );
      block.invalid = true;
      continue;
    }

    let pendingChoice = null;

    for (let cmdIndex = 0; cmdIndex < commands.size; cmdIndex++) {
      const cmd = listElem(commands, cmdIndex);
      if (!valid(cmd)) continue;

      const type = readStr(rp(cmd, OFF.cmd_type));
      const row = rp(cmd, OFF.cmd_row);

      if (type !== 'Selection') {
        pendingChoice = flushChoice(pendingChoice, block.items);
      }

      if (type === 'Selection') {
        if (pendingChoice === null) {
          pendingChoice = {
            kind: 'choice',
            order: { labelIndex, pageNo, cmdIndex, subIndex: 0 },
            branches: [],
          };
        }

        pendingChoice.branches.push({
          option: shortText(readRowColumn(row, COL.raw)),
          target: readStr(rp(cmd, OFF.selection_jumpLabel)),
          order: {
            labelIndex,
            pageNo,
            cmdIndex,
            subIndex: pendingChoice.branches.length,
          },
        });
        continue;
      }

      if (type === 'Jump' || type === 'JumpRandom' || type === 'JumpSubroutine') {
        const target = readStr(rp(cmd, OFF.jump_jumpLabel));
        if (!target) continue;

        const jump = {
          type,
          target,
          condition: readRowColumn(row, COL.condition),
          pageIndex,
          order: { labelIndex, pageNo, cmdIndex, subIndex: 0 },
        };
        block.jumps.push(jump);

        if (type === 'Jump' && jump.condition) {
          block.items.push({
            kind: 'if',
            order: jump.order,
            condition: jump.condition,
            target: jump.target,
          });
        }
      }
    }

    flushChoice(pendingChoice, block.items);
  }

  block.jumps.sort((a, b) => {
    if (a.pageIndex !== b.pageIndex) return a.pageIndex - b.pageIndex;
    if (a.order.pageNo !== b.order.pageNo) return a.order.pageNo - b.order.pageNo;
    if (a.order.cmdIndex !== b.order.cmdIndex) return a.order.cmdIndex - b.order.cmdIndex;
    return a.order.subIndex - b.order.subIndex;
  });

  for (const jump of block.jumps) {
    if (jump.type !== 'Jump') {
      console.log(
        `[ERROR] unsupported jump label="${block.label}" type=${jump.type}` +
        ` target="${jump.target}" order=${formatOrder(jump.order)}`
      );
      block.invalid = true;
    }
  }

  for (let i = block.jumps.length - 1; i >= 0; i--) {
    const jump = block.jumps[i];
    if (jump.type === 'Jump' && !jump.condition && jump.target) {
      block.continuation = jump.target;
      block.hasTerminalJump = true;
      break;
    }
  }

  if (!block.hasTerminalJump) {
    block.continuation = block.nextLabel;
  }

  return block;
}

function mergeIfConditions(first, second) {
  if (!first || first === second) return second || first;
  if (!second) return first;
  return `(${first})||(${second})`;
}

function coalesceParallelIfEdges(block) {
  const normalized = [];
  const targets = new Map();

  for (const item of block.items) {
    if (item.kind !== 'if' || !item.target) {
      normalized.push(item);
      continue;
    }

    if (!targets.has(item.target)) {
      targets.set(item.target, item);
      normalized.push(item);
      continue;
    }

    const existing = targets.get(item.target);
    existing.condition = mergeIfConditions(existing.condition, item.condition);
    console.log(
      `[COALESCE IF] owner="${block.label}" target="${item.target}"` +
      ` condition="${existing.condition}"`
    );
  }

  block.items = normalized;
}

function buildContinuationChain(start, blocks, byName) {
  if (!start) return { ok: false, chain: [], reason: 'empty-start' };

  const chain = [];
  const visited = new Set();
  let current = start;

  for (let step = 0; step <= blocks.length + 1; step++) {
    if (!current) {
      chain.push(VIRTUAL_EXIT);
      return { ok: true, chain };
    }

    if (visited.has(current)) {
      return { ok: false, chain, reason: `loop:${current}` };
    }
    visited.add(current);
    chain.push(current);

    const block = byName.get(current);
    if (!block) {
      chain.push(VIRTUAL_EXIT);
      return { ok: true, chain, reason: `missing:${current}` };
    }

    current = block.continuation;
  }

  return { ok: false, chain, reason: 'step-limit' };
}

function collectBranchPath(start, merge, blocks, byName) {
  if (!start || !merge) return { ok: false, path: [], reason: 'empty-endpoint' };

  const path = [];
  const visited = new Set();
  let current = start;

  for (let step = 0; step <= blocks.length; step++) {
    if (current === merge) return { ok: true, path };
    if (!current) {
      return { ok: merge === VIRTUAL_EXIT, path, reason: 'reached-exit' };
    }
    if (visited.has(current)) return { ok: false, path, reason: `loop:${current}` };
    visited.add(current);

    const block = byName.get(current);
    if (!block) {
      return { ok: merge === VIRTUAL_EXIT, path, reason: `missing:${current}` };
    }

    path.push(block);
    current = block.continuation;
  }

  return { ok: false, path, reason: 'step-limit' };
}

function findChoiceMerge(choice, blocks, byName) {
  if (!choice.branches || choice.branches.length === 0) {
    return { ok: false, merge: '', chains: [], reason: 'no-branches' };
  }

  const chains = [];
  for (const branch of choice.branches) {
    if (!branch.target) {
      return { ok: false, merge: '', chains, reason: 'empty-target' };
    }

    const result = buildContinuationChain(branch.target, blocks, byName);
    chains.push(result.chain);
    if (!result.ok) {
      return { ok: false, merge: '', chains, reason: result.reason };
    }
  }

  if (chains.length === 1) {
    return { ok: true, merge: VIRTUAL_EXIT, chains };
  }

  const reachable = chains.slice(1).map((chain) => new Set(chain));
  for (const candidate of chains[0]) {
    if (reachable.every((labels) => labels.has(candidate))) {
      return { ok: true, merge: candidate, chains };
    }
  }

  return { ok: false, merge: '', chains, reason: 'no-common-merge' };
}

function findIfMerge(trueStart, falseStart, blocks, byName) {
  const trueResult = buildContinuationChain(trueStart, blocks, byName);
  if (!trueResult.ok) {
    return { ok: false, merge: '', trueChain: trueResult.chain, falseChain: [], reason: trueResult.reason };
  }

  let falseResult;
  if (falseStart === VIRTUAL_EXIT) {
    falseResult = { ok: true, chain: [VIRTUAL_EXIT] };
  } else {
    falseResult = buildContinuationChain(falseStart, blocks, byName);
  }
  if (!falseResult.ok) {
    return {
      ok: false,
      merge: '',
      trueChain: trueResult.chain,
      falseChain: falseResult.chain,
      reason: falseResult.reason,
    };
  }

  const falseReachable = new Set(falseResult.chain);
  for (const candidate of trueResult.chain) {
    if (falseReachable.has(candidate)) {
      return {
        ok: true,
        merge: candidate,
        trueChain: trueResult.chain,
        falseChain: falseResult.chain,
      };
    }
  }

  return {
    ok: false,
    merge: '',
    trueChain: trueResult.chain,
    falseChain: falseResult.chain,
    reason: 'no-common-merge',
  };
}

function logChoiceConflict(owner, branchIndex, branch, block, reason, detail) {
  console.log(
    `[CONFLICT CHOICE] owner="${owner.label}" ownerIndex=${owner.index}` +
    ` branch=${branchIndex + 1} target="${branch.target}"` +
    ` label="${block.label}" labelIndex=${block.index}` +
    ` reason=${reason}${detail ? ` ${detail}` : ''}`
  );
}

function resolveChoice(owner, choice, blocks, byName) {
  const mergeResult = findChoiceMerge(choice, blocks, byName);
  console.log(
    `[CHOICE] owner="${owner.label}" ownerIndex=${owner.index}` +
    ` order=${formatOrder(choice.order)} branches=${choice.branches.length}` +
    ` merge="${mergeResult.merge || '?'}"`
  );

  for (let i = 0; i < choice.branches.length; i++) {
    const branch = choice.branches[i];
    console.log(
      `[CHOICE CHAIN] branch=${i + 1} target="${branch.target}"` +
      ` option="${branch.option}" chain=${formatLabels(mergeResult.chains[i] || [])}`
    );
  }

  if (!mergeResult.ok) {
    console.log(`[ABORT CHOICE] owner="${owner.label}" reason=${mergeResult.reason}`);
    return { ok: false, merge: '' };
  }

  const claimedBy = new Map();
  const paths = [];

  for (let i = 0; i < choice.branches.length; i++) {
    const branch = choice.branches[i];
    const pathResult = collectBranchPath(branch.target, mergeResult.merge, blocks, byName);
    console.log(
      `[CHOICE PATH] branch=${i + 1} target="${branch.target}"` +
      ` merge="${mergeResult.merge}" path=${formatLabels(pathResult.path)}`
    );

    if (!pathResult.ok) {
      console.log(
        `[ABORT CHOICE] owner="${owner.label}" branch=${i + 1}` +
        ` reason=invalid-path:${pathResult.reason}`
      );
      return { ok: false, merge: mergeResult.merge };
    }

    for (const pathBlock of pathResult.path) {
      if (pathBlock.index <= owner.index) {
        logChoiceConflict(owner, i, branch, pathBlock, 'backward', '');
        return { ok: false, merge: mergeResult.merge };
      }
      if (pathBlock.consumed) {
        logChoiceConflict(
          owner,
          i,
          branch,
          pathBlock,
          'consumed',
          `consumedBy="${pathBlock.consumedBy}"`
        );
        return { ok: false, merge: mergeResult.merge };
      }
      if (claimedBy.has(pathBlock.label)) {
        logChoiceConflict(
          owner,
          i,
          branch,
          pathBlock,
          'claimed',
          `claimedByBranch=${claimedBy.get(pathBlock.label) + 1}`
        );
        return { ok: false, merge: mergeResult.merge };
      }

      claimedBy.set(pathBlock.label, i);
    }

    paths.push(pathResult.path);
  }

  for (let branchIndex = 0; branchIndex < paths.length; branchIndex++) {
    for (const pathBlock of paths[branchIndex]) {
      pathBlock.consumed = true;
      pathBlock.consumedBy =
        `choice owner=${owner.label} order=${formatOrder(choice.order)} branch=${branchIndex + 1}`;
    }
  }

  console.log(
    `[RESOLVED CHOICE] owner="${owner.label}" merge="${mergeResult.merge}"` +
    ` branches=${choice.branches.length}`
  );
  return { ok: true, merge: mergeResult.merge };
}

function resolveIf(owner, item, blocks, byName) {
  const falseStart = owner.continuation || VIRTUAL_EXIT;
  const mergeResult = findIfMerge(item.target, falseStart, blocks, byName);

  console.log(
    `[IF] owner="${owner.label}" ownerIndex=${owner.index}` +
    ` order=${formatOrder(item.order)} condition="${item.condition}"` +
    ` target="${item.target}" false="${falseStart}"` +
    ` merge="${mergeResult.merge || '?'}"` +
    ` trueChain=${formatLabels(mergeResult.trueChain)}` +
    ` falseChain=${formatLabels(mergeResult.falseChain)}`
  );

  if (!mergeResult.ok) {
    console.log(`[ABORT IF] owner="${owner.label}" reason=${mergeResult.reason}`);
    return false;
  }

  const pathResult = collectBranchPath(item.target, mergeResult.merge, blocks, byName);
  if (!pathResult.ok) {
    console.log(
      `[ABORT IF] owner="${owner.label}" reason=invalid-path:${pathResult.reason}` +
      ` path=${formatLabels(pathResult.path)}`
    );
    return false;
  }

  for (const pathBlock of pathResult.path) {
    if (pathBlock.index <= owner.index) {
      console.log(
        `[CONFLICT IF] owner="${owner.label}" target="${item.target}"` +
        ` label="${pathBlock.label}" reason=backward`
      );
      return false;
    }
    if (pathBlock.consumed) {
      console.log(
        `[CONFLICT IF] owner="${owner.label}" target="${item.target}"` +
        ` label="${pathBlock.label}" reason=consumed` +
        ` consumedBy="${pathBlock.consumedBy}"`
      );
      return false;
    }
  }

  for (const pathBlock of pathResult.path) {
    pathBlock.consumed = true;
    pathBlock.consumedBy =
      `if owner=${owner.label} order=${formatOrder(item.order)} target=${item.target}`;
  }

  console.log(
    `[RESOLVED IF] owner="${owner.label}" target="${item.target}"` +
    ` merge="${mergeResult.merge}" path=${formatLabels(pathResult.path)}`
  );
  return true;
}

function simulateAssembly(blocks) {
  const byName = new Map(blocks.map((block) => [block.label, block]));

  for (const block of blocks) {
    coalesceParallelIfEdges(block);
  }

  for (let reverseIndex = blocks.length - 1; reverseIndex >= 0; reverseIndex--) {
    const block = blocks[reverseIndex];
    let continuationFromChoice = false;

    for (let itemIndex = block.items.length - 1; itemIndex >= 0; itemIndex--) {
      const item = block.items[itemIndex];

      if (item.kind === 'choice') {
        const result = resolveChoice(block, item, blocks, byName);
        if (!result.ok) return false;

        if (!block.hasTerminalJump && !continuationFromChoice) {
          block.continuation = result.merge === VIRTUAL_EXIT ? '' : result.merge;
          continuationFromChoice = true;
          console.log(
            `[CONTINUATION UPDATE] label="${block.label}"` +
            ` source=choice continuation="${block.continuation || VIRTUAL_EXIT}"`
          );
        }
        continue;
      }

      if (item.kind === 'if' && !resolveIf(block, item, blocks, byName)) {
        return false;
      }
    }
  }

  return true;
}

function dumpScenarioBranchGraph(scenarioData, requestedLabel) {
  if (!valid(scenarioData)) {
    console.log(`[FindScenarioData] requested="${requestedLabel}" scenarioData=NULL`);
    return;
  }

  const scenarioName = readStr(rp(scenarioData, OFF.scenarioData_name));
  if (
    SCENARIO_FILTER &&
    !scenarioName.includes(SCENARIO_FILTER) &&
    !requestedLabel.includes(SCENARIO_FILTER)
  ) {
    return;
  }

  const requestKey = `${scenarioData}|${requestedLabel}`;
  if (dumpedRequests.has(requestKey)) {
    console.log(
      `[FindScenarioData] requested="${requestedLabel}" scenarioData=${scenarioData} already dumped`
    );
    return;
  }
  dumpedRequests.add(requestKey);

  const labels = enumerateScenarioLabels(scenarioData);
  const orderResult = buildLabelOrder(labels, scenarioName, requestedLabel);
  const blocks = orderResult.ordered.map((item, index) => parseLabelBlock(item, index));

  console.log(
    `[SCENARIO GRAPH] requested="${requestedLabel}" scenario="${scenarioName}"` +
    ` scenarioData=${scenarioData} labels=${labels.length}` +
    ` order=${blocks.length} start="${orderResult.start}"` +
    ` appendedUnvisited=${orderResult.unvisited.length}`
  );

  if (orderResult.unvisited.length > 0) {
    console.log(
      `[APPENDED UNVISITED] ${orderResult.unvisited.map((item) => item.label).join(', ')}`
    );
  }

  for (const block of blocks) {
    const choiceCount = block.items.filter((item) => item.kind === 'choice').length;
    const ifCount = block.items.filter((item) => item.kind === 'if').length;
    console.log(
      `[BLOCK ${block.index}/${blocks.length - 1}] label="${block.label}"` +
      ` next="${block.nextLabel}" continuation="${block.continuation || VIRTUAL_EXIT}"` +
      ` terminal=${block.hasTerminalJump ? 1 : 0}` +
      ` jumps=${block.jumps.length} choices=${choiceCount} ifs=${ifCount}`
    );

    for (const jump of block.jumps) {
      console.log(
        `[JUMP] owner="${block.label}" order=${formatOrder(jump.order)}` +
        ` type=${jump.type} target="${jump.target}" condition="${jump.condition}"`
      );
    }
  }

  if (blocks.some((block) => block.invalid)) {
    console.log('[ASSEMBLY ABORT] invalid block data');
    return;
  }

  const ok = simulateAssembly(blocks);
  const consumed = blocks.filter((block) => block.consumed).length;
  console.log(
    `[ASSEMBLY ${ok ? 'OK' : 'ABORT'}] requested="${requestedLabel}"` +
    ` consumed=${consumed} roots=${blocks.length - consumed}`
  );
}

function install() {
  const module = Process.findModuleByName('libil2cpp.so');
  if (!module) {
    setTimeout(install, 500);
    return;
  }

  gBase = module.base;
  const target = gBase.add(RVA.DataManagerFindScenarioData);
  console.log(`[+] libil2cpp base=${gBase}`);
  console.log(
    `[+] hook AdvDataManager.FindScenarioData @ ${target}` +
    ` rva=0x${RVA.DataManagerFindScenarioData.toString(16)}`
  );

  Interceptor.attach(target, {
    onEnter(args) {
      this.requestedLabel = readStr(args[1]);
      console.log(`-> #${++gSeq} FindScenarioData requested="${this.requestedLabel}"`);
    },
    onLeave(retval) {
      dumpScenarioBranchGraph(retval, this.requestedLabel || '');
    },
  });
}

console.log('[*] diag_scenario_branch_graph waiting libil2cpp...');
install();
