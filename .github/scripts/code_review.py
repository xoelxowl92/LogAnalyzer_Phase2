import subprocess, json, urllib.request, os, sys, glob, time

# 첫 번째 커밋이면 스킵
check = subprocess.run(['git', 'rev-parse', 'HEAD~1'], capture_output=True)
if check.returncode != 0:
    print("First commit, skipping review")
    sys.exit(0)

diff = subprocess.run(
    ['git', 'diff', 'HEAD~1', 'HEAD'],
    capture_output=True, text=True
).stdout

# 관련 확장자만 필터링
relevant_exts = {'.java', '.properties', '.yml', '.yaml', '.xml', '.sql'}
filtered_lines = []
include = False
for line in diff.splitlines():
    if line.startswith('diff --git'):
        include = any(ext in line for ext in relevant_exts)
    if include:
        filtered_lines.append(line)

diff_text = '\n'.join(filtered_lines)[:8000]

if not diff_text.strip():
    print("No relevant file changes, skipping")
    sys.exit(0)

# 설계 문서 읽기
doc_paths = sorted(
    glob.glob('docs/architecture/*.md') +
    glob.glob('docs/design/*.md')
)
docs_text = ""
for path in doc_paths:
    with open(path, 'r', encoding='utf-8') as f:
        content = f.read()
    docs_text += f"\n\n### [{path}]\n{content}"

docs_text = docs_text[:20000]

# 프롬프트 구성
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

# Gemini API 호출
api_key = os.environ['GEMINI_API_KEY']
payload = {
    "contents": [{"parts": [{"text": prompt}]}]
}

url = f"https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent?key={api_key}"

data = None
for attempt in range(1, 4):
    req = urllib.request.Request(
        url,
        data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"}
    )
    try:
        with urllib.request.urlopen(req) as resp:
            data = json.loads(resp.read())
        break
    except urllib.error.HTTPError as e:
        error_body = e.read().decode()
        print(f"Gemini API error {e.code} (attempt {attempt}/3): {error_body}")
        if e.code == 503 and attempt < 3:
            time.sleep(10 * attempt)
        else:
            sys.exit(1)

if data is None:
    sys.exit(1)

review = data["candidates"][0]["content"]["parts"][0]["text"]

# 커밋 댓글 등록
comment = "## 🤖 Gemini 코드 리뷰\n\n" + review

req = urllib.request.Request(
    f"https://api.github.com/repos/{os.environ['REPO']}/commits/{os.environ['COMMIT_SHA']}/comments",
    data=json.dumps({"body": comment}).encode(),
    headers={
        "Authorization": f"Bearer {os.environ['GH_TOKEN']}",
        "Content-Type": "application/json",
        "Accept": "application/vnd.github+json"
    }
)

with urllib.request.urlopen(req) as resp:
    print(f"Review posted successfully (status: {resp.status})")
