import os
import sys
import re
import asyncio
import subprocess
from pathlib import Path
from google.antigravity import Agent, LocalAgentConfig

DIFF_CHAR_LIMIT = 30000
MAX_WAIT_TIME_SECONDS = 60.0  # Limite máximo de espera para não travar o runner de CI

def run_git_command(args: list[str], strip: bool = True) -> str:
    """Executa comando git de forma segura com encoding resiliente. Lança exceção em caso de falha."""
    try:
        result = subprocess.run(
            args,
            text=True,
            encoding="utf-8",
            errors="replace",
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE
        )
        return result.stdout.strip() if strip else result.stdout
    except subprocess.CalledProcessError as e:
        error_msg = e.stderr.strip() if e.stderr else str(e)
        print(f"Erro ao executar {' '.join(args)}: {error_msg}", file=sys.stderr)
        raise RuntimeError(f"Falha ao executar comando Git: {' '.join(args)}") from e

def get_git_diff(base_ref: str) -> tuple[str, str]:
    """Obtém as estatísticas e o diff dos arquivos modificados de forma robusta e independente."""
    target_ref = f"origin/{base_ref}...HEAD"
    
    # Executa comandos dedicados para evitar parsing frágil de strings combinadas
    diff_stat = run_git_command(["git", "diff", "--stat", target_ref])
    diff_text = run_git_command(["git", "diff", "-p", target_ref], strip=False)

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

    try:
        diff_stat, diff_output = get_git_diff(base_ref)
    except Exception as e:
        print(f"Falha na extração do diff: {e}", file=sys.stderr)
        sys.exit(1)

    if not diff_output.strip():
        print("Nenhuma alteração de código encontrada para revisar.")
        return

    system_instructions = load_system_instructions()
    config = LocalAgentConfig(system_instructions=system_instructions)
    prompt = prepare_diff_prompt(diff_stat, diff_output)

    final_review = ""
    max_attempts = 3
    base_delay = 3

    for attempt in range(max_attempts):
        try:
            # Instancia o agente a cada tentativa para garantir sessão 100% limpa
            async with Agent(config) as agent:
                response = await agent.chat(prompt)
                review_body = []
                async for token in response:
                    review_body.append(token)

                candidate_text = "".join(review_body).strip()
                if not candidate_text:
                    raise RuntimeError("O modelo retornou uma resposta vazia.")

                final_review = candidate_text
                break
        except Exception as e:
            error_str = str(e)
            is_quota_error = "429" in error_str or "RESOURCE_EXHAUSTED" in error_str

            wait_time = base_delay * (2 ** attempt)
            if is_quota_error:
                match = re.search(r"retry in (\d+(?:\.\d+)?)s", error_str, re.IGNORECASE)
                if match:
                    requested_delay = float(match.group(1)) + 1.0
                    if requested_delay > MAX_WAIT_TIME_SECONDS:
                        print(
                            f"[Aviso] Limite de cota atingido com tempo de espera excessivo ({requested_delay:.1f}s). "
                            "Ignorando review para não prender o runner do CI.",
                            file=sys.stderr
                        )
                        return
                    wait_time = max(wait_time, requested_delay)

            if attempt < max_attempts - 1:
                print(f"Tentativa {attempt + 1} falhou ({e}). Tentando novamente em {wait_time:.1f}s...", file=sys.stderr)
                await asyncio.sleep(wait_time)
            else:
                if is_quota_error:
                    print(
                        "[Aviso] Limite de cota da API Gemini atingido (HTTP 429). "
                        "Ignorando review nesta execução para não bloquear o pipeline de CI.",
                        file=sys.stderr
                    )
                    return
                print(f"Todas as {max_attempts} tentativas falharam: {e}", file=sys.stderr)
                raise e

    Path("review_output.md").write_text(final_review, encoding="utf-8")
    print("Revisão gerada com sucesso em review_output.md.")

if __name__ == "__main__":
    asyncio.run(run_review())
