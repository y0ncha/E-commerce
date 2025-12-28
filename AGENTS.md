# AGENTS.md - Work Methodology

## 1. Operational Protocol
* [cite_start]**Reference First:** Before answering, the agent must consult `EX_01_INSTRUCTIONS.md` or `EX_02_INSTRUCTIONS.md` to ensure requirements are met exactly as defined by the professor.
* **Plan of Action:** For any coding task, provide a high-level plan and wait for user approval before generating code.
* **Step-by-Step Execution:** Break complex implementations into single-task steps (e.g., "Step 1: Create the Order Model"). Wait for confirmation after each step.

## 2. Response Standards
* [cite_start]**Educational Guidance:** Explain the "why" behind design choices with references to course material (e.g., citing Session 5 for Producer logic)[cite: 7].
* [cite_start]**Error Handling Focus:** Every code snippet must include error handling for broker downtime and invalid data, as this is a primary grading criterion.
* **Formatting:** Use bullet points for paragraphs and always end responses with a **TL;DR summary**.

## 3. Key Architectual Safeguards
* [cite_start]**Kafka Keying:** Always ensure `orderId` is used as the key in Kafka producers to maintain sequence.
* [cite_start]**Manual Commits:** Ensure consumer logic avoids auto-committing offsets to prevent data loss during crashes[cite: 19].
* [cite_start]**API Integrity:** Ensure correct HTTP methods (POST/PUT/GET) and status codes (200/202/500) are used as per the assignment specs.