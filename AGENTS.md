# AGENTS.md - Work Methodology & Protocol

## 0. Project Context (IMPORTANT)
* **School Assignment:** This is an academic course assignment (EDA course, MTA).
* **Code Quality:** The code itself is **not** being reviewed for production standards. Focus is on **learning Kafka and Spring** concepts.
* **Simplicity First:** Keep implementations **simple and straightforward**. Avoid over-engineering, excessive design patterns, or premature optimization.
* **Learning Goals:** Master Kafka message keying, ordering guarantees, manual offset commits, and Spring Boot Kafka integration.

## 1. Project Goal & Scope
* **Primary Objective:** Build an Event-Driven E-Commerce System using **Apache Kafka** (Exercise 2).
* **Context:** This project is an evolution of a previous assignment.
  * **Exercise 1 (RabbitMQ):** Provided for reference/historical context only. **DO NOT** implement RabbitMQ logic unless explicitly requested.
  * **Exercise 2 (Kafka):** This is the **active** development target. All code, configuration, and architectural decisions must align with Kafka patterns (Topics, Partitions, Offsets).

## 2. Operational Protocol
* **Reference First:** Before answering, consult `Ex.02.md` for the current requirements. Only check `Ex.01.md` if the user asks for comparisons.
* **Plan of Action:** For any coding task, provide a high-level plan and wait for user approval before generating code.
* **Step-by-Step Execution:** Break complex implementations into single-task steps (e.g., "Step 1: Create the Order Model"). Wait for confirmation after each step.
* **Keep It Simple:** Choose straightforward implementations that demonstrate learning without unnecessary complexity.

## 3. Response Standards
* **Educational Guidance:** Explain the "why" behind design choices with references to course material (e.g., citing *Session 5* for Producer logic).
* **Error Handling Focus:** Include basic error handling for broker downtime and invalid data. Focus on correctness, not exhaustive edge cases.
* **Formatting:** Use bullet points for paragraphs and always end responses with a **TL;DR summary**.

## 4. Critical Architectural Safeguards (Kafka Focus)
* **Kafka Keying:** Always ensure `orderId` is used as the **Message Key** in producers. This is non-negotiable for preserving event order.
* **Manual Commits:** Consumer logic must use `EnableAutoCommit = false` and manual acknowledgments to ensure "At-Least-Once" delivery.
* **No RabbitMQ Mix-ups:** Do not use terms like "Exchanges" or "Routing Keys" when discussing the current system. Use "Topics" and "Partition Keys".

