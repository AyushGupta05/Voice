# Luma

**Make scientific software conversational.**

Luma is a native companion for QuPath, inspired by [Clicky](https://github.com/farzaa/clicky). It bridges a researcher's intent and the application's actual state through a reusable agent interface. The researcher keeps using the original scientific software.

Built for [Reinvent the Wheel: Tools for Science](https://luma.com/vdq0qw9u), 16–18 October 2026.

![Actual QuPath viewer with threshold-positive cells on the public OS-2 sample](docs/images/ki67-positive-view.png)

## Current prototype

- A floating macOS companion follows the active image and selected objects, highlights work and shows action history.
- **30 MCP tools** expose context, objects, measurements, navigation, display, analysis, recovery, live menus, JavaFX dialogs and reviewed Groovy execution.
- Data changes require explicit approval bound to the reviewed arguments and application state. Agents cannot approve their own actions.
- Authored task guidance and the locally downloaded official QuPath documentation provide searchable application knowledge.
- A real public-slide fixture detected **899 cells**, classified **49** above a chosen DAB measurement threshold, compared results and restored the empty object hierarchy.

Those counts verify software operation for the fixture. They do not validate segmentation or biological interpretation. See [data provenance](docs/DATASETS.md).

**Production voice is not yet enabled.** The user requires AWS credits only. No credit-covered OpenAI realtime/GPT-Live endpoint has been verified. The separate Realtime and GPT-Live transports and injected backend have local protocol tests, but provider/native capture wiring and live verification remain. Production activation stays disabled. The companion now has an AWS-credit OpenAI text path with follow-ups and Stop. Its real authenticated message-route test focused QuPath correctly; the new native controls compile, with foreground visual checks pending manual unlock. Across two real text tests, token usage estimates total $0.0028011 and $6 is conservatively reserved; AWS billing settlement remains unreconciled. No direct cash-billed OpenAI route or cloud infrastructure was used. [Funding decision](docs/FUNDING.md)

## Start locally

Apple Silicon macOS, Xcode command-line tools, Node.js 22+, Git and Python 3.9+ are required.

```sh
python3 script/setup.py
./script/run-qupath.sh
./script/build_and_run.sh --verify
```

The bootstrap verifies pinned official downloads and keeps the JDK, QuPath, upstream source and documentation in `.local`. It installs nothing globally. The local QuPath launcher needs the full JDK because its stock runtime omits the extension's HTTP module.

Open an image in QuPath and select an object or region. Use Luma's quick actions, or connect an MCP client using the [setup guide](docs/SETUP.md). Risky actions appear in the companion for review.

## Understand and extend it

| Start here | Purpose |
|---|---|
| [Product brief](docs/PRODUCT.md) | Problem, accepted direction and scope |
| [Setup](docs/SETUP.md) | Install, launch, MCP configuration and troubleshooting |
| [Tool reference](docs/TOOL-REFERENCE.md) | Current schemas, parameters and approval requirements |
| [Task coverage](docs/SOFTWARE-COVERAGE.md) | Import, analysis and navigation routes with proof boundaries |
| [Capability matrix](docs/CAPABILITIES.md) | Implemented, verified and blocked behavior |
| [Architecture](docs/ARCHITECTURE.md) | Process ownership and trust boundaries |
| [AWS text adapter](docs/AWS-TEXT.md) | Credit/access checks, conservative reserves and explicit model test |
| [Voice integration](docs/VOICE.md) | Implemented protocols, backend interface and remaining activation work |
| [Protocol](docs/PROTOCOL.md) | Requests, tool execution and approval semantics |
| [Contributor guide](docs/CONTRIBUTING.md) | Add adapters/tools and verify changes |
| [QuPath agent skill](skills/qupath/SKILL.md) | Scientific operating guidance and task references |
| [Demo guide](docs/DEMO.md) | Run the demonstration honestly |
| [Editable pitch deck](docs/pitch/README.md) | Seven slides with actual application evidence |
| [Review and verification](docs/REVIEW.md) | Commands, accepted fixes and proof boundaries |
| [Build journal](docs/BUILD-JOURNAL.md) | Decisions, evidence and outstanding work |

## Repository map

- `apps/companion/`: SwiftUI/AppKit floating interface, pointer and audio components.
- `packages/gateway/`: TypeScript tool validation, MCP, approvals, local events and knowledge.
- `adapters/qupath/`: Java 25 extension using the actual QuPath APIs.
- `skills/qupath/`: source-backed application guidance.
- `script/`: reproducible setup, build, launch, smoke and guarded fixture checks.
- `docs/`: architecture, evidence, contributor and pitch material.

`npm run doctor` checks local setup and connections without changing the application. `npm run check` runs focused tests, type checking and tool-reference consistency. `npm run smoke` exercises a real MCP client through the running gateway and QuPath without changing data. See the contributor guide before running real mutation fixtures.

Original project code retains the existing Apache-2.0 license. QuPath and the JDK retain their upstream licenses. This repository does not relicense upstream QuPath source. Public sample imagery has separate provenance in the dataset guide.
