---
name: ship
description: Planner → Coder → Tester → Reviewer 에이전트 팀을 순서대로 실행해 요청을 구현하고 검증한다.
argument-hint: <구현할 요청 내용>
disable-model-invocation: true
---

요청: $ARGUMENTS

아래 순서로 서브에이전트를 하나씩 호출한다. 각 단계는 이전 단계의 결과 파일을 읽는다.

1. **planner** — 요청을 전달하고 `.pipeline/spec.md`를 작성하게 한다. 열린 질문이 있으면 중단하고 사용자에게 묻는다.
2. **coder** — `.pipeline/spec.md`를 구현하고 `.pipeline/changes.md`를 작성하게 한다.
3. **tester** — `.pipeline/test-results.md`를 작성하게 한다.
4. **reviewer** — `.pipeline/review.md`를 작성하게 한다.

## 재작업 규칙
- 판정이 NEEDS WORK이면 coder를 다시 호출한다: "review.md와 test-results.md에 적힌 문제만 수정하고 다른 부분은 바꾸지 마." 이후 tester → reviewer를 다시 실행한다. 최대 2회까지 반복한다.
- 판정이 BLOCK이면 멈추고 사유를 사용자에게 알린다.

## 규칙
- 사용자가 명시적으로 요청하지 않으면 git commit, push, 배포를 하지 않는다.
- 마지막에 최종 판정과 변경 요약을 사용자에게 보고한다.
