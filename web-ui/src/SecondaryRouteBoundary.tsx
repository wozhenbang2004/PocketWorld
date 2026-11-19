import { Component, type ReactNode } from 'react';
import { Button } from './forms-library';
import { Skeleton } from './library';

export function SecondaryRoutePending({ onBack }: { onBack: () => void }) {
  return <section className="pw-compact-empty" aria-label="打开工作区">
    <div aria-hidden="true"><Skeleton width={110} height={10}/></div>
    <p role="status">正在打开工作区</p>
    <Button variant="ghost" onClick={onBack}>返回</Button>
  </section>;
}

type Props = { navigation: string; onBack: () => void; children: ReactNode };
type State = { navigation: string; failed: boolean };

export class SecondaryRouteBoundary extends Component<Props, State> {
  state: State = { navigation: this.props.navigation, failed: false };
  static getDerivedStateFromError(): Partial<State> { return { failed: true }; }
  static getDerivedStateFromProps(props: Props, state: State): Partial<State> | null {
    return props.navigation === state.navigation ? null : { navigation: props.navigation, failed: false };
  }
  render() {
    if (!this.state.failed) return this.props.children;
    return <section className="pw-compact-empty" aria-label="工作区未打开">
      <p role="alert">没能打开这个工作区。可以返回，其他工作仍在原处。</p>
      <Button variant="secondary" onClick={this.props.onBack}>返回</Button>
    </section>;
  }
}
