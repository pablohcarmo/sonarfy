import os
import sys
import asyncio
import subprocess
from pathlib import Path
from google.antigravity import Agent, LocalAgentConfig

DIFF_CHAR_LIMIT = 30000

def run_git_command(args: list[str]) -> str:
    """Executa comando git de forma segura."""
    try:
        return subprocess.check_output(args, text=True, stderr=subprocess.PIPE).strip()
    except subprocess.CalledProcessError as e:
        print(f"Erro ao executar {' '.join(args)}: {e.stderr.strip()}", file=sys.stderr)
        return ""

def get_git_diff(base_ref: str) -> tuple[str, str]:
    """Obtém o diff e as estatísticas dos arquivos modificados."""
    target_ref = f"origin/{base_ref}...HEAD"
    diff_stat = run_git_command(["git", "diff", "--stat", target_ref])
    diff_text = run_git_command(["git", "diff", target_ref])
    return diff_stat, diff_text

def load_system_instructions() -> str:
    """Lê as diretrizes de AGENTS.md se disponível, ou usa fallback."""
    base_instructions = (
        "Você é um engenheiro de software sênior realizando code review em um Pull Request no GitHub.\n"
        "Analise o diff fornecido, identifique riscos de bugs, segurança e oportunidades de melhoria.\n"
        "Formate a resposta em Markdown com resumo executivo, pontos de atenção e sugestões de código.\n"
    )
    agents_file = Path("AGENTS.md")
    if agents_file.is_file():
        guidelines = agents_file.read_text(encoding="utf-8")
        return f"{base_instructions}\n\nDiretrizes Operacionais do Projeto:\n{guidelines}"
    return base_instructions

def prepare_diff_prompt(diff_stat: str, diff_text: str) -> str:
    """Monta o prompt incluindo estatísticas e truncamento defensivo."""
    header = f"Resumo dos arquivos alterados:\n```text\n{diff_stat}\n```\n\n"

    if len(diff_text) <= DIFF_CHAR_LIMIT:
        return f"Analise o seguinte git diff e faça uma revisão de código:\n\n{header}```diff\n{diff_text}\n```"

    cutoff = diff_text.rfind("\n", 0, DIFF_CHAR_LIMIT)
    cutoff = cutoff if cutoff != -1 else DIFF_CHAR_LIMIT
    truncated = diff_text[:cutoff]

    return (
        "Analise o seguinte git diff e faça uma revisão de código.\n"
        "AVISO: O diff excedeu o limite máximo e foi truncado abaixo.\n\n"
        f"{header}```diff\n{truncated}\n```\n\n"
        "[... diff truncado por limite de tamanho ...]"
    )

async def run_review():
    api_key = os.getenv("GEMINI_API_KEY")
    if not api_key:
        print("GEMINI_API_KEY não configurada. Review ignorado.")
        return

    base_ref = os.getenv("GITHUB_BASE_REF", "main")
    diff_stat, diff_output = get_git_diff(base_ref)

    if not diff_output.strip():
        print("Nenhuma alteração de código encontrada para revisar.")
        return

    system_instructions = load_system_instructions()
    config = LocalAgentConfig(system_instructions=system_instructions)

    async with Agent(config) as agent:
        prompt = prepare_diff_prompt(diff_stat, diff_output)

        final_review = ""
        max_attempts = 3
        for attempt in range(max_attempts):
            try:
                response = await agent.chat(prompt)
                review_body = []
                async for token in response:
                    review_body.append(token)
                final_review = "".join(review_body)
                break
            except Exception as e:
                wait_time = 2 ** (attempt + 1)
                if attempt < max_attempts - 1:
                    print(f"Tentativa {attempt + 1} falhou ({e}). Tentando novamente em {wait_time}s...")
                    await asyncio.sleep(wait_time)
                else:
                    raise e

        if final_review.strip():
            Path("review_output.md").write_text(final_review, encoding="utf-8")
            print("Revisão gerada com sucesso em review_output.md.")

if __name__ == "__main__":
    asyncio.run(run_review())