# Foreground demo

Luma keeps QuPath as the scientific application. Its default companion surface is a 420 × 56 pixel bar at the top of the screen. Click the status or chevron to type a request, read results or inspect measurements. Pending approvals expand the details and stay visible until decided. Stop cancels unstarted agent work; completed actions remain.

The bar uses short factual states: Connecting, Open QuPath, Reconnecting, Working in QuPath, Review action and Listening. Listening requires active voice state; the current funding gate keeps production voice off. A microphone icon or a successful text request is not evidence of a working live audio session.

## Load the latest version safely

1. Save current work and quit QuPath normally. Keep recovery copies separate from source images.
2. Run `./script/build-bridge.sh` and `./script/run-qupath.sh`.
3. Run `./script/build_and_run.sh --verify`. This restarts only this project's companion; its owned gateway exits through normal shutdown. An externally owned gateway must be handled separately after its work completes.
4. Run `npm run doctor` and `npm run smoke`. The loaded bridge identity and running schemas must match the current source before testing new operations.

## Opening an image end to end

Expand Luma and ask it to open an exact existing local image path. For the downloaded public fixture:

> Open the image /Users/ayush/dev/Voice/.local/data/OS-2.ndpi in QuPath. Do not change image settings or scientific objects. Keep the reply short and verify the resulting active image.

The agent prepares `qupath_open_path`. Review the exact path in Luma and approve it yourself. Native QuPath reader dialogs may need attention. A `dispatched` response is only a request sent to QuPath: verify the window title, actual image metadata, `context.lastDispatch` and visible viewer before calling it successful. An unsaved current image must be saved before switching.

Then try a view-only request:

> Focus the viewer at image pixels x=51100, y=27100, downsample=3. Confirm the returned center.

Full analysis changes still require human approvals. The previously authorized automated OS-2 and synthetic-import fixtures remain narrowly scoped test workflows; this demo guide grants no automatic approval power.

## Voice boundary

OpenAI on Bedrock currently provides the verified reasoning path. GPT-Live is a separate voice connection whose AWS credit coverage has not been established. The proposed alternative is Amazon Transcribe → OpenAI on Bedrock → Amazon Polly; it requires an explicit provider choice, verified service credit eligibility/prices, bounded reservations and actual microphone/playback testing before activation. Do not label text-only or synthetic audio tests as end-to-end live voice.

Foreground results and any provider decision belong in the build journal and readiness guide after actual verification.
