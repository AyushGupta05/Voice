# Product brief

## The promise

Researchers already know the question they want to ask. They should not need to memorize a scientific application's interface or scripting language to carry it out.

Penny bridges that learning gap: a small conversational companion sits beside QuPath, understands the selected image and objects, and performs real operations. Voice is the primary interaction; text and a visible action history make the work inspectable.

## Accepted direction

- QuPath is the required first application.
- Strong Clicky influence: friendly floating companion, visible pointer, short responses, action first.
- Continuous OpenAI GPT-Live conversation with interruption and a separate scientific backend.
- Broad application access through structured tools, live command discovery, searchable knowledge and approval-gated scripting, rather than one hardcoded demo.
- Risky operations require explicit researcher approval.
- Documentation should let another contributor install, extend, test and understand the entire stack.
- Demonstrate real image/selection awareness and actual application changes.
- Never invent measurements or describe simulated results as live scientific evidence.

## Funding constraints

User authorizes at most $1,000 of AWS credits total and at most $200 before waking roughly seven hours after the 00:45 BST build start on 17 October. No cash billing is authorized. Credit eligibility must be positively verified before any paid resource is used. AWS hosting does not by itself make an external OpenAI API request AWS-credit-funded.

## Milestones

1. Verify integration and funding; install upstream locally; document decisions.
2. Implement and test the QuPath bridge and MCP tool surface.
3. Build the native companion, pointer and approval experience.
4. Implement GPT-Live/backend protocols, then activate a provider only after verified credit coverage and native voice testing.
5. Exercise real QuPath workflows; fix bugs; run tests and autoreview.
6. Publish incremental commits, setup instructions, knowledge and honest demo evidence.

## Scope truth

"Anything in QuPath" means a discoverable command catalog and explicit extension paths, not a claim that every installed plugin or modal dialog has already been verified. The capability matrix must distinguish tested structured tools, approval-gated commands, and unsupported operations.
