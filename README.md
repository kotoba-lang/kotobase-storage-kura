# kotobase-storage-kura

`kotobase.storage` over the [kura](https://github.com/kotoba-lang/kura) shard
plane, so every consumer already speaking that contract — including DataLad
datasets reaching it through `kotoba-annex` — gets kura without learning a new
API. Design: [ADR-2607299200](https://github.com/com-junkawasaki/root/blob/main/90-docs/adr/2607299200-kura-erasure-coded-storage-network.edn).

The ADR described this repo as "a thin layer holding none of the decisions".
Writing it surfaced two facts that description did not account for. Both are
load-bearing, and both are corrections to the ADR rather than to the code.

## 1. kura cannot provide refs, and does not pretend to

`kotobase.storage` is two protocols: immutable CID blocks, and a mutable ref
with compare-and-set. kura is a shard plane — objects, placement groups,
erasure coding — with no linearizable CAS primitive anywhere in it. A backend
that declared `:single-writer-ref` and shrugged would be doing exactly what
`kotobase.storage/ref-profiles` warns about: *an ignored precondition returns
success.*

So `open` **requires** a `:ref-store` delegate — another `kotobase.storage`
backend that really has a CAS — and this adapter reports **that delegate's**
ref profile rather than inventing one. Without a delegate it refuses to
construct.

Blocks come from kura. Refs come from something that can do refs. Nothing
claims to do both.

## 2. kura's 1.625× does not apply at kotobase block sizes

kotobase cuts blocks at 16–128 KB. Erasure-coding a 128 KB block at k=16 gives
8 KB shards, twenty-six of them, and a read that touches sixteen nodes — for
128 KB. A replica costs one request.

| block size | strategy | multiplier | reads |
|---|---|---|---|
| 64 KiB | replication ×3 | 3.0 | 1 |
| 64 MiB | erasure k=16/n=26 | 1.625 | 16 |

So there is an `:erasure-threshold-bytes` (default 1 MiB, where sixteen shard
reads start to amortise): at or above it, erasure-code; below it, replicate.

**Quoting 1.625× to a consumer whose blocks are all 64 KB would be a lie about
what they are buying** — it is 3× for that workload. `effective-multiplier`
reports what a real distribution actually costs, because the honest number for
a mixed workload is a mixture and not either constant:

```clojure
(sut/effective-multiplier pol sizes)
;; {:blocks 100 :multiplier 2.06 :erasure-coded 10 :replicated 90 ...}
```

## Durability is the fleet's, not the code's

`durability-report` delegates to `kura.node.store/audit`. The question a
kotobase consumer asks is how durable their data is, and the answer is how many
genuinely independent failure domains the fleet has — not what the erasure
code's parameters say. A fleet of pseudo-nodes on one account reports one
domain here, which is the point.

## Tests

```bash
kbb -M:test
kbb -M:cljs -m cljs.main --target node -m kotobase.storage.cljs-runner
kbb -M:lint
```

The suite runs `kotobase.storage.contract/verify` — the upstream conformance
suite — so passing is a statement about the contract, not about this repo's
own opinion of it.

## License

MIT.
