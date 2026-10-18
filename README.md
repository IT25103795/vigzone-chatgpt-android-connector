# Vigzone Android Connector

An open-source, phone-local client for OpenAI's documented **Sign in with ChatGPT** and Responses API flow. The native connector can be embedded in a Trusted Web Activity without giving the web page or its server OAuth credentials. This component is separate from the private Vigzone web application.

## Availability

OpenAI currently documents ChatGPT plan usage for eligible Plus and Pro accounts. A Free identity login does not grant inference access. The connector cannot grant entitlement, bypass consent, or guarantee approval of a particular client or account. OpenAI remains the authority on eligibility, usage and distribution requirements. Publishing this component does not claim that the private Vigzone service has partner approval.

## Integration

Build as an Android library (Java 17, Android SDK 35, minimum API 23). Use `PostMessageTwaLauncher` from an existing Android Browser Helper `LauncherActivity.createTwaLauncher()` implementation. Pass the exact trusted HTTPS origin. Keep the original launcher Activity lifecycle and normal TWA fallback.

An optional `PostMessageTwaLauncher.Host` receives verified `app` RPCs and browser visibility changes. Hosts can implement task backgrounding and synchronize their existing notification preference without exposing OAuth credentials. The native hello advertises `lifecycle:1` only when that host is installed. Register `ChatGptConnector.setCompletionListener` from the host Application to deliver a completion alert directly from the saved generation job, independently of a live page. The host must enforce its active account, opt-in setting and OS notification permission, suppress foreground alerts and clear alerts on return. Callback failure never removes the encrypted reply.

Publish a Digital Asset Links statement for the consuming app's original signing certificate with `delegate_permission/common.use_as_origin` alongside `delegate_permission/common.handle_all_urls`. A debug certificate is not trusted by the production origin. Chrome 115+ and successful origin validation are required for the message channel.

The host's JavaScript receives a transferred MessagePort in the initial `{connector:"vigzone-chatgpt",version:1}` message. Verify the exact event origin and use this port for JSON RPC: `{id,method,params:{accountId,...}}`. Methods: `status`, `login`, `models`, `chat`, `cancel`, `disconnect`, `recover`, `ack`. Responses echo `id`; streamed text uses `{id,event:"delta",delta}`. No method returns credentials. `accountId` must identify the host's active signed-in account; keep model selection and verified connection identity scoped to that account.

`chat` requires `requestId`, `conversationId`, `model`, `expectedAccount`, `text` and optional user/assistant `history`. Select a model from this account's returned catalog. Only a received `response.completed` commits success. The last completed reply is encrypted locally until acknowledged, allowing the host to recover a reply after a channel interruption. Android process termination can interrupt network generation; callers must not label an interrupted response complete.

The user signs in through the system browser. First registration uses `dynamic_agent_client`, a stable opaque host identifier and the consistent name **Vigzone Android Connector**. The loopback listener binds only `127.0.0.1`, validates one-time state and PKCE, and expires after five minutes. The client validates the ID token's RSA signature, issuer, audience, expiry and nonce before saving tokens. Reauthorization retains the issued client ID and account identity. OAuth credentials and completed replies use Android Keystore AES-GCM encryption with account-specific associated data, app-private storage, and no browser/server exposure. Host apps must disable credential backup.

Disconnect forgets the local connection. Users can separately revoke the registered client's access in ChatGPT settings. ChatGPT account changes must be explicitly confirmed in the host UI. No API-key fallback is performed automatically.

## Limitations

This first version provides streamed text conversations. It does not expose hosted ChatGPT tools, existing ChatGPT conversations, image generation, transcription or computer control. The model catalog comes from OpenAI, not hardcoded model entitlements. Real-account consent and inference require an eligible user's interactive sign-in; fake test credentials are never presented as a working connection.

## Sources

- https://developers.openai.com/siwc/token-sharing-open-source/sign-in
- https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference
- https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations
- https://developer.chrome.com/docs/android/post-message-twa

License: Apache-2.0. Android Browser Helper and AndroidX are used under their respective licenses. No private Vigzone web source, account data, signing keys or credentials are included in this component.
