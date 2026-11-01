import { FlowAnimator, HOP_MS, MAX_PARTICLES } from './flow-animator';
import { Hop } from './flow-logic';

const publication: Hop[] = [
  { from: 'p', to: 'r', stage: 0 },
  { from: 'r', to: 'd', stage: 1 },
];

describe('FlowAnimator', () => {
  let animator: FlowAnimator;

  beforeEach(() => (animator = new FlowAnimator()));

  it('moves a point along its hop in proportion to the time that passes', () => {
    animator.add([publication[0]], false);

    animator.advance(HOP_MS / 2);

    expect(animator.particles).toEqual([{ key: 0, from: 'p', to: 'r', progress: 0.5, unrouted: false }]);
  });

  it('keeps the second hop waiting until the first ends', () => {
    animator.add(publication, false);
    animator.advance(HOP_MS / 2);
    expect(animator.particles.map((p) => p.to)).toEqual(['r']);

    animator.advance(HOP_MS / 2 + 1);

    expect(animator.particles.map((p) => p.to)).toEqual(['d']);
  });

  it('removes the points that have arrived', () => {
    animator.add(publication, false);

    animator.advance(HOP_MS * 2 + 1);

    expect(animator.particles).toEqual([]);
    expect(animator.active).toBe(false);
  });

  it('is active while something is moving or waiting', () => {
    expect(animator.active).toBe(false);
    animator.add(publication, false);
    expect(animator.active).toBe(true);
  });

  it('marks the points of an unrouted publication', () => {
    animator.add([publication[0]], true);

    expect(animator.particles[0].unrouted).toBe(true);
  });

  describe('pause', () => {
    it('freezes the points where they are and resumes from there', () => {
      animator.add([publication[0]], false);
      animator.advance(HOP_MS / 2);

      animator.paused = true;
      animator.advance(10_000);
      expect(animator.particles[0].progress).toBe(0.5);
      expect(animator.active).toBe(false);

      animator.paused = false;
      animator.advance(HOP_MS / 4);
      expect(animator.particles[0].progress).toBe(0.75);
    });

    it('does not animate the events that arrive while paused', () => {
      animator.paused = true;

      expect(animator.add(publication, false)).toBe(false);
      animator.paused = false;

      expect(animator.particles).toEqual([]);
    });
  });

  describe('slow motion', () => {
    it('moves the points at the chosen fraction of the real speed', () => {
      animator.speed = 0.1;
      animator.add([publication[0]], false);

      animator.advance(HOP_MS);

      expect(animator.particles[0].progress).toBeCloseTo(0.1);
    });

    it('lets a point take ten times longer to arrive', () => {
      animator.speed = 0.1;
      animator.add([publication[0]], false);

      animator.advance(HOP_MS * 9);
      expect(animator.particles).toHaveLength(1);

      animator.advance(HOP_MS * 2);
      expect(animator.particles).toHaveLength(0);
    });
  });

  describe('reduced motion', () => {
    it('creates no points at all', () => {
      animator.reducedMotion = true;

      expect(animator.add(publication, false)).toBe(false);
      expect(animator.particles).toEqual([]);
      expect(animator.active).toBe(false);
    });
  });

  it('ignores an event without hops', () => {
    expect(animator.add([], false)).toBe(false);
  });

  it('never keeps more than the maximum number of points, dropping the oldest', () => {
    for (let i = 0; i < MAX_PARTICLES + 100; i++) {
      animator.add([publication[0]], false);
    }

    const particles = animator.particles;
    expect(particles).toHaveLength(MAX_PARTICLES);
    expect(particles[0].key).toBe(100);
  });
});
