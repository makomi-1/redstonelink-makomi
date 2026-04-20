import { act, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import RecordingChart from './RecordingChart';

const { mockPlotInstances, MockUPlot } = vi.hoisted(() => {
  const mockPlotInstances: MockUPlot[] = [];

  class MockUPlot {
    static paths = {
      stepped: vi.fn(() => undefined),
    };

    readonly over = document.createElement('div');

    readonly bbox = {
      left: 10,
      top: 14,
      width: 320,
      height: 420,
    };

    cursor: {
      left: number;
      top: number;
      idx: number | null;
    } = {
      left: 0,
      top: 0,
      idx: null,
    };

    constructor(
      private readonly options: Record<string, unknown>,
      readonly data: unknown[],
      host: HTMLElement,
    ) {
      host.appendChild(this.over);
      mockPlotInstances.push(this);
    }

    setScale = vi.fn();

    destroy = vi.fn();

    posToVal(value: number): number {
      return value;
    }

    /**
     * 测试场景直接模拟 uPlot 的 cursor hook 回调。
     */
    emitCursor(nextCursor: Partial<MockUPlot['cursor']>): void {
      this.cursor = {
        ...this.cursor,
        ...nextCursor,
      };
      const hookList =
        (
          this.options.hooks as { setCursor?: Array<(self: MockUPlot) => void> } | undefined
        )?.setCursor ?? [];
      hookList.forEach((hook) => hook(this));
    }
  }

  return {
    mockPlotInstances,
    MockUPlot,
  };
});

vi.mock('uplot', () => ({
  default: MockUPlot,
}));

describe('RecordingChart', () => {
  beforeEach(() => {
    mockPlotInstances.length = 0;
  });

  it('空曲线时不会显示 tooltip', () => {
    const { container } = render(
      <RecordingChart
        language="zh-CN"
        nodeSeriesGroups={[]}
        startedTick={10}
        xMode="relative"
        renderMode="linear"
        themeId="future-command"
        visibleMetrics={['inputPower']}
        xWindow={{ min: 0, max: 1 }}
        fullDomain={{ min: 0, max: 1 }}
        onWindowChange={vi.fn()}
        onResetWindow={vi.fn()}
      />,
    );

    expect(container.querySelector('.recording-chart-tooltip')).toBeNull();
  });

  it('cursor 命中样本时会显示 tooltip，并在 cursor 清空后隐藏', async () => {
    const { container } = render(
      <RecordingChart
        language="zh-CN"
        nodeSeriesGroups={[
          {
            nodeKey: 'node-a',
            label: '节点A',
            inputColor: '#66ccff',
            outputColor: '#ffaa66',
            samples: [
              { tick: 10, inputPower: 3, outputPower: 4 },
              { tick: 11, inputPower: 5, outputPower: 6 },
            ],
          },
        ]}
        startedTick={10}
        xMode="relative"
        renderMode="linear"
        themeId="future-command"
        visibleMetrics={['inputPower', 'outputPower']}
        xWindow={{ min: 0, max: 1 }}
        fullDomain={{ min: 0, max: 1 }}
        onWindowChange={vi.fn()}
        onResetWindow={vi.fn()}
      />,
    );

    const plot = mockPlotInstances[0];
    expect(plot).toBeDefined();

    act(() => {
      plot.emitCursor({
        idx: 1,
        left: 120,
        top: 80,
      });
    });

    expect(await screen.findByText('节点A · 输入')).toBeInTheDocument();
    expect(screen.getByText('节点A · 输出')).toBeInTheDocument();
    expect(screen.getByText('Tick 差值')).toBeInTheDocument();
    expect(screen.getByText('5')).toBeInTheDocument();
    expect(screen.getByText('6')).toBeInTheDocument();
    expect(container.querySelector('.recording-chart-tooltip')).not.toBeNull();

    act(() => {
      plot.emitCursor({
        idx: null,
      });
    });

    await waitFor(() => {
      expect(container.querySelector('.recording-chart-tooltip')).toBeNull();
    });
  });
});
