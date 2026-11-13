"""Mesures descriptives d'une base synthétique, sans exporter les emails."""
import argparse
import collections
import json
import re
import sqlite3
import statistics
from pathlib import Path


def summarize(values):
    return {"min": min(values), "median": statistics.median(values),
            "mean": round(statistics.mean(values), 3), "max": max(values)}


def analyze(database):
    expected = {"commercial": "CommercialAgent", "integration": "IntegrationAgent",
                "sur-site": "OnsiteSupportAgent", "administration": "AccountAdminAgent",
                "general": "GenericAgent"}
    uri = database.resolve().as_uri() + "?mode=ro"
    with sqlite3.connect(uri, uri=True) as connection:
        generations = [json.loads(row[0]) for row in connection.execute(
            "SELECT payload FROM completed_generations ORDER BY sequence")]
        drafts = [json.loads(row[0]) for row in connection.execute("SELECT payload FROM drafts")]
        if not generations:
            raise ValueError("Aucune génération achevée dans cette base")
        elapsed, inputs, outputs, words = [], [], [], []
        by_agent, by_category = collections.Counter(), collections.Counter()
        matches = 0
        for item in generations:
            result = item["result"]
            reply = result["reply"]
            if reply["simulated"]:
                raise ValueError("La base contient une génération simulée")
            elapsed.append(float(re.fullmatch(r"PT([0-9.]+)S", reply["elapsed"])[1]))
            inputs.append(reply["inputTokens"])
            outputs.append(reply["outputTokens"])
            words.append(len(re.findall(r"\b\w+(?:[’'-]\w+)*\b", reply["text"])))
            by_agent[result["agentId"]] += 1
            message_id = result["receipt"]["routing"]["messageId"]
            category = next((name for name in expected if re.search(
                "-" + re.escape(name) + r"-\d+$", message_id)), None)
            if category is None:
                raise ValueError("Message ne provenant pas du corpus synthétique attendu")
            by_category[category] += 1
            matches += result["agentId"] == expected[category]
        return {
            "dataset": "Flux synthétique de vingt emails, quatre par catégorie",
            "generation_count": len(generations), "draft_count": len(drafts),
            "messages": connection.execute("SELECT count(*) FROM messages").fetchone()[0],
            "work_states": dict(connection.execute(
                "SELECT state,count(*) FROM generation_work GROUP BY state")),
            "approvals": sum(review["type"] == "APPROVED" for draft in drafts
                             for review in draft["reviews"]),
            "send_attempts": sum(len(draft["sendAttempts"]) for draft in drafts),
            "simulated_outbox": connection.execute(
                "SELECT count(*) FROM simulated_outbox").fetchone()[0],
            "by_agent": dict(sorted(by_agent.items())),
            "by_category": dict(sorted(by_category.items())),
            "routing_matches_expected_category": matches,
            "elapsed_seconds": summarize(elapsed),
            "input_tokens": summarize(inputs), "output_tokens": summarize(outputs),
            "response_words": summarize(words), "responses_at_most_120_words": sum(n <= 120 for n in words),
            "settings": generations[0]["settings"],
            "method_limits": ["Corpus de démonstration, pas un jeu de test indépendant",
                              "Durée du gateway, pas latence complète depuis réception",
                              "Comptage lexical automatique, pas jugement de qualité"]}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = analyze(args.database)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=True, indent=2))
