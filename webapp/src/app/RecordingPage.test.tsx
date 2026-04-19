import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import RecordingPage from './RecordingPage';
import type { RecordingBundle } from '../recordingTypes';
import { createTestStorageEntryPayload } from '../test/factories';

vi.mock('../components/RecordingViewer', () => ({
  default: () => <div data-testid="recording-viewer">recording-viewer</div>,
}));

vi.mock('../components/ThemeSwitcher', () => ({
  default: () => <div data-testid="theme-switcher">theme-switcher</div>,
}));

const recordingBundle: RecordingBundle = {
  kind: 'recordingBundle',
  manifest: {
    recordingId: 'recording-1',
    title: 'demo recording',
    startedTick: 100,
    endedTick: 120,
    sampleEveryTicks: 1,
    nodeCount: 1,
    sampleCount: 2,
    formatVersion: 1,
  },
  nodes: [
    {
      nodeKey: 'triggerSource:1',
      type: 'triggerSource',
      serial: 1,
      displayText: 'node-1',
      traceKind: 'default',
      allocated: true,
      retired: false,
      online: true,
    },
  ],
  series: [
    {
      nodeKey: 'triggerSource:1',
      samples: [
        {
          tick: 100,
          online: true,
          active: false,
          inputPower: 0,
          outputPower: 0,
        },
        {
          tick: 101,
          online: true,
          active: true,
          inputPower: 15,
          outputPower: 15,
        },
      ],
    },
  ],
  markers: [],
};

describe('RecordingPage', () => {
  it('有合法 recordingBundle 时会将连接状态与分析器标识放在页头右侧区域', () => {
    render(
      <RecordingPage
        bridgeError=""
        bridgeStateClassName="status-pill is-ready"
        bridgeStateLabel="已连接"
        onThemeChange={vi.fn()}
        onRecordingFileChange={vi.fn()}
        onRefreshStorageIndex={vi.fn()}
        recordingBundle={recordingBundle}
        recordingEntries={[]}
        recordingEntryError=""
        recordingEntryLoading={false}
        recordingSelectedEntry={createTestStorageEntryPayload({
          kind: 'recording',
          fileName: 'demo-recording.json',
          textContent: '{"kind":"recordingBundle"}',
        })}
        selectedFileName="demo-recording.json"
        storageError=""
        storageLoading={false}
        themeId="future-command"
      />,
    );

    expect(screen.getByText('已连接')).toBeInTheDocument();
    expect(screen.getByText('Dedicated Recording Viewer')).toBeInTheDocument();
    expect(screen.getByTestId('recording-viewer')).toBeInTheDocument();
  });
});
