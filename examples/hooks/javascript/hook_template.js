#!/usr/bin/env node
/**
 * JavaScript Hook 单文件模板。
 * 支持 PRE_REQUEST / POST_RESPONSE / POST_PARSED 三个阶段。
 *
 * 说明：
 * - 输入 JSON 通过 stdin 传入
 * - 输出 JSON 通过 stdout 返回
 * - 调试信息请输出到 stderr，不要污染 stdout
 */

function readStdin() {
  return new Promise((resolve, reject) => {
    const chunks = [];
    process.stdin.on("data", (c) => chunks.push(c));
    process.stdin.on("end", () => resolve(Buffer.concat(chunks).toString("utf8")));
    process.stdin.on("error", reject);
  });
}

function stripFence(text) {
  const raw = (text || "").trim();
  const m = raw.match(/^```(?:json)?\s*([\s\S]*?)\s*```$/i);
  return m ? m[1].trim() : raw;
}

function handlePreRequest(payload) {
  const src = payload.sourceLanguage || "";
  const tgt = payload.targetLanguage || "";
  const extra = `\n[Hook追加要求]\n- source=${src}\n- target=${tgt}\n- 输出必须是严格 JSON`;
  return {
    systemPrompt: String(payload.systemPrompt || "") + extra,
    userPrompt: String(payload.userPrompt || "") + "\n请仅输出 JSON。"
  };
}

function handlePostResponse(payload) {
  return { rawResponse: stripFence(String(payload.rawResponse || "")) };
}

function handlePostParsed(payload) {
  const entries = payload.entries && typeof payload.entries === "object" ? payload.entries : {};
  return { entries };
}

async function main() {
  const input = await readStdin();
  const payload = input.trim() ? JSON.parse(input) : {};
  const stage = String(payload.stage || "").toUpperCase();

  let output;
  if (stage === "PRE_REQUEST") {
    output = handlePreRequest(payload);
  } else if (stage === "POST_RESPONSE") {
    output = handlePostResponse(payload);
  } else if (stage === "POST_PARSED") {
    output = handlePostParsed(payload);
  } else {
    output = payload;
  }

  process.stdout.write(JSON.stringify(output));
}

main().catch((err) => {
  process.stderr.write(`[hook-error] ${err && err.message ? err.message : String(err)}\n`);
  process.exit(1);
});

