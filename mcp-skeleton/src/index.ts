// 문항 은행 MCP 서버 골격 (TypeScript SDK v2 · stdio)
//
// stdio 서버에서는 stdout 이 곧 프로토콜 채널이다.
// 로그는 console.error 만 쓴다(stdout 으로 찍는 로그 함수는 절대 쓰지 않는다).
import { McpServer } from '@modelcontextprotocol/server';
import { StdioServerTransport } from '@modelcontextprotocol/server/stdio';

const server = new McpServer({ name: 'item-bank', version: '1.0.0' });

// 예시 도구: 서버가 살아 있는지 확인한다. 입력 없음.
server.registerTool(
  'ping',
  {
    description: '서버 연결 확인용 예시 도구. "pong" 을 돌려준다.'
  },
  async () => ({
    content: [{ type: 'text', text: 'pong' }]
  })
);

// ================================================================
// 여기에 도구를 등록합니다
//
//   server.registerTool('도구_이름', { description, inputSchema }, handler)
//
// - inputSchema 는 z.object({...}) 전체 스키마로 넘긴다 (import * as z from 'zod/v4')
// - 감쌀 API 는 ./itemApi.js 에 있다 (ESM 이라 확장자를 .js 로 적는다)
// - import 문도 이 자리에 함께 붙여 넣어도 된다
// ================================================================
import * as z from 'zod/v4';
import { searchItems } from './itemApi.js';

server.registerTool(
  'search_items',
  {
    description:
      '문항 은행에서 초등 수학 문항을 키워드로 찾을 때 쓴다. ' +
      '사용자가 특정 개념 · 계산 유형 · 태그(예: "분수", "문장제", "오답률높음")의 문항을 찾거나, ' +
      '단원 · 난이도별 문항 예시를 요청할 때 호출한다. 조회 전용이며 문항을 만들거나 고치지 않는다.',
    inputSchema: z.object({
      keyword: z.string().describe('검색어. 문항 본문(stem)이나 태그에 포함되면 일치한다. 빈 문자열이면 키워드 조건 없이 검색한다.'),
      unit: z.string().optional().describe("단원 코드('M5-1' 등) 또는 단원 이름 일부('분수의 곱셈' 등)"),
      difficulty: z.enum(['하', '중', '상']).optional().describe("난이도. '하' · '중' · '상' 중 하나"),
      limit: z.number().int().min(1).max(20).default(5).describe('최대 결과 건수(1~20, 기본 5)')
    })
  },
  async ({ keyword, unit, difficulty, limit }) => {
    const items = await searchItems({ keyword, unit, difficulty, limit });
    console.error(`search_items: keyword=${JSON.stringify(keyword)} → ${items.length}건`);
    const body =
      items.length === 0
        ? { message: '검색 결과 없음', conditions: { keyword, unit, difficulty, limit } }
        : { count: items.length, items };
    return {
      content: [{ type: 'text', text: JSON.stringify(body) }]
    };
  }
);

async function main() {
  const transport = new StdioServerTransport();
  await server.connect(transport);
  console.error('item-bank MCP server running on stdio');
}

main().catch((error) => {
  console.error('Fatal error:', error);
  process.exit(1);
});
