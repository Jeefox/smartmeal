import { spawn } from "node:child_process";
import { readFile } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { setTimeout as delay } from "node:timers/promises";

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const args = process.argv.slice(2);
const chains = (
  args.find((x) => x.startsWith("--chains="))?.slice(9) ||
  "magnit,globus,perekrestok,pyaterochka"
).split(",");
if (
  !chains.length ||
  chains.some(
    (c) => !["magnit", "globus", "perekrestok", "pyaterochka"].includes(c),
  )
) {
  throw new Error("INVALID_CHAIN");
}
// One browser at a time. A failed source does not prevent other networks updating.
do {
  for (const chain of chains) {
    if (args.includes("--watch")) {
      let status;
      try {
        status = JSON.parse(
          await readFile(
            join(ROOT, "data/runtime", chain, "status.json"),
            "utf8",
          ),
        );
      } catch {}
      const expectedScope = args.includes("--all")
        ? "ALL_DISCOVERED_CATEGORIES"
        : "FOOD_CATEGORIES";
      if (status && (!status.success || status.scope === expectedScope)) {
        const time = Date.parse(
          status.success ? status.retrievedAt : status.attemptedAt,
        );
        if (Date.now() - time < (status.success ? 7 : 1) * 86400000) continue;
      }
    }
    const forwarded = args.filter((a) =>
      ["--headed", "--force", "--all"].includes(a),
    );
    const code = await new Promise((resolve, reject) => {
      const child = spawn(
        process.execPath,
        [join(ROOT, "scripts/collect_retail.mjs"), chain, ...forwarded],
        { cwd: ROOT, stdio: "inherit" },
      );
      child.on("error", reject);
      child.on("exit", (code) => resolve(code ?? 1));
    });
    if (code !== 0) {
      console.error(
        `${chain}: collection failed; complete category snapshots and previous prices retained`,
      );
      if (!args.includes("--watch")) process.exitCode = 1;
    }
  }
  if (!args.includes("--watch")) break;
  await delay(3600000);
} while (true);
