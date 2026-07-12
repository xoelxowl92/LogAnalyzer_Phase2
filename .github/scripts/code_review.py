import subprocess, json, urllib.request, os, sys, glob, time

RELEVANT_EXTS = {'.java', '.properties', '.yml', '.yaml', '.xml', '.sql'}


def get_commit_diff(parent, commit):
    diff = subprocess.run(
        ['git', 'diff', parent, commit],
        capture_output=True, text=True
    ).stdout

    filtered_lines = []
    include = False
    for line in diff.splitlines():
        if line.startswith('diff --git'):
            include = any(ext in line for ext in RELEVANT_EXTS)
        if include:
            filtered_lines.append(line)

    return '\n'.join(filtered_lines)[:8000]


def build_prompt(diff_text, docs_text):
    prompt = "당신은 시니어 백엔드 개발자입니다.\n"
    prompt += "아래 설계 문서를 기반으로, 코드 변경사항이 설계 의도에 맞게 구현되었는지 리뷰해주세요.\n\n"
    prompt += "---\n## 프로젝트 설계 문서\n"
    prompt += docs_text
    prompt += "\n\n---\n## 코드 변경사항 (diff)\n"
    prompt += "```diff\n"
    prompt += diff_text
    prompt += "\n```\n\n"
    prompt += "---\n## 리뷰 항목\n"
    prompt += "- 설계 문서와 구현 간 불일치\n"
    prompt += "- 버그 가능성 또는 로직 오류\n"
    prompt += "- 코드 품질 및 가독성\n"
    prompt += "- 성능 / 보안 이슈\n"
    prompt += "- 개선 제안\n\n"
    prompt += "항목별로 간결하게 작성해주세요."
    return prompt


def call_gemini(prompt, api_key):
    payload = {"contents": [{"parts": [{"text": prompt}]}]}
    url = f"https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent?key={api_key}"

    for attempt in range(1, 4):
        req = urllib.request.Request(
            url,
            data=json.dumps(payload).encode(),
            headers={"Content-Type": "application/json"}
        )
        try:
            with urllib.request.urlopen(req) as resp:
                data = json.loads(resp.read())
            return data["candidates"][0]["content"]["parts"][0]["text"]
        except urllib.error.HTTPError as e:
            error_body = e.read().decode()
            print(f"Gemini API error {e.code} (attempt {attempt}/3): {error_body}")
            if e.code == 503 and attempt < 3:
                time.sleep(10 * attempt)
            else:
                sys.exit(1)

    sys.exit(1)


def post_commit_comment(repo, commit, body, gh_token):
    req = urllib.request.Request(
        f"https://api.github.com/repos/{repo}/commits/{commit}/comments",
        data=json.dumps({"body": body}).encode(),
        headers={
            "Authorization": f"Bearer {gh_token}",
            "Content-Type": "application/json",
            "Accept": "application/vnd.github+json"
        }
    )
    with urllib.request.urlopen(req) as resp:
        print(f"Review posted for {commit} (status: {resp.status})")


def get_push_commits(before_sha, after_sha):
    # 새 브랜치 최초 push는 before가 전부 0인 SHA로 온다
    if before_sha and set(before_sha) != {'0'}:
        result = subprocess.run(
            ['git', 'rev-list', '--reverse', f'{before_sha}..{after_sha}'],
            capture_output=True, text=True
        )
        if result.returncode == 0 and result.stdout.strip():
            return result.stdout.split()
    return [after_sha]


def main():
    before_sha = os.environ.get('BEFORE_SHA', '')
    after_sha = os.environ['AFTER_SHA']
    repo = os.environ['REPO']
    gh_token = os.environ['GH_TOKEN']
    api_key = os.environ['GEMINI_API_KEY']

    doc_paths = sorted(
        glob.glob('docs/architecture/*.md') +
        glob.glob('docs/design/*.md')
    )
    docs_text = ""
    for path in doc_paths:
        with open(path, 'r', encoding='utf-8') as f:
            docs_text += f"\n\n### [{path}]\n{f.read()}"
    docs_text = docs_text[:20000]

    commits = get_push_commits(before_sha, after_sha)

    for commit in commits:
        parent_check = subprocess.run(['git', 'rev-parse', f'{commit}~1'], capture_output=True)
        if parent_check.returncode != 0:
            print(f"{commit}: first commit, skipping review")
            continue

        diff_text = get_commit_diff(f'{commit}~1', commit)
        if not diff_text.strip():
            print(f"{commit}: no relevant file changes, skipping")
            continue

        prompt = build_prompt(diff_text, docs_text)
        review = call_gemini(prompt, api_key)
        comment = "## 🤖 Gemini 코드 리뷰\n\n" + review
        post_commit_comment(repo, commit, comment, gh_token)


if __name__ == '__main__':
    main()
