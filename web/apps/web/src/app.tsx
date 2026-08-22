import { Link, createBrowserRouter, type RouteObject } from "react-router-dom";

function HomePage() {
  return (
    <main className="min-h-screen bg-neutral-950 text-neutral-50">
      <h1 className="text-2xl font-bold">legion</h1>
      <p className="text-neutral-400">人和 AI 智能体通过任务协作的工作区</p>
      <Link to="/issues" className="underline">
        进入任务列表
      </Link>
    </main>
  );
}

function IssuesListPage() {
  return <main>IssueListPage（占位）</main>;
}

function IssueDetailPage() {
  return <main>IssueDetailPage（占位）</main>;
}

export const routes: RouteObject[] = [
  { path: "/", element: <HomePage /> },
  { path: "/issues", element: <IssuesListPage /> },
  { path: "/issues/:issueId", element: <IssueDetailPage /> },
];

export function createRouter() {
  return createBrowserRouter(routes, {
    future: { v7_relativeSplatPath: true },
  });
}