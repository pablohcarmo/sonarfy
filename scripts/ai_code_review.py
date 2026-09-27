import os
import sys
import asyncio
import subprocess
from google.antigravity import Agent, LocalAgentConfig

async def run_review():
    # Obtém o diff do branch em relação ao branch base (ex: main)
    base_ref = os.getenv("GITHUB_BASE_REF", "main")
    diff_command = f"git diff origin/{base_ref}...HEAD"
    diff_output = subprocess.check_output(diff_command, shell=True, text=True)

    if not diff_output.strip():
        print("Nenhuma alteração de código para revisar.")
        return

    # Limita o diff se necessário para otimizar tokens
    truncated_diff = diff_output[:12000]

    system_instructions = (
        "Você é um engenheiro sênior realizando code review em um Pull Request no GitHub. "
        "Analise o diff fornecido, identifique riscos de bugs, segurança e oportunidades de melhoria. "
        "Formate a resposta em Markdown com resumo executivo, pontos de atenção e sugestões de código."
    )

    config = LocalAgentConfig(
        system_instructions=system_instructions
    )

    async with Agent(config) as agent:
        prompt = f"Analise o seguinte git diff e faça uma revisão de código:\n\n```diff\n{truncated_diff}\n```"
        response = await agent.chat(prompt)

        review_body = []
        async for token in response:
            review_body.append(token)

        final_review = "".join(review_body)

        # Salva o resultado para o GitHub Actions consumir
        with open("review_output.md", "w", encoding="utf-8") as f:
            f.write(final_review)

if __name__ == "__main__":
    asyncio.run(run_review())