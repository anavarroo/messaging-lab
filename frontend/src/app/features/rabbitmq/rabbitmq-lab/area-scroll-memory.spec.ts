import { ViewportScroller } from '@angular/common';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { Component } from '@angular/core';
import { FakeViewportScroller } from '../../../testing/fake-viewport-scroller';
import { AreaScrollMemory, areaOf } from './area-scroll-memory';

@Component({ template: '' })
class Page {}

describe('areaOf', () => {
  it.each([
    ['/rabbitmq/guide', 'guide'],
    ['/rabbitmq/exchanges', 'exchanges'],
    ['/rabbitmq/queues?x=1', 'queues'],
    ['/rabbitmq/guide#ejemplo', 'guide'],
    ['/rabbitmq/consumers/otra/cosa', 'consumers'],
  ])('%s belongs to the area %s', (url, area) => {
    expect(areaOf(url)).toBe(area);
  });

  it.each(['/status', '/', '/rabbitmq', '/rabbitmq/', '/otro/rabbitmq/guide'])('%s belongs to no area', (url) => {
    expect(areaOf(url)).toBeNull();
  });
});

describe('AreaScrollMemory', () => {
  let router: Router;
  let scroller: FakeViewportScroller;
  let harness: RouterTestingHarness;
  let memory: AreaScrollMemory;

  beforeEach(async () => {
    scroller = new FakeViewportScroller();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { path: 'status', component: Page },
          {
            path: 'rabbitmq',
            component: Page,
            providers: [AreaScrollMemory],
            children: [
              { path: 'guide', component: Page },
              { path: 'exchanges', component: Page },
              { path: 'queues', component: Page },
            ],
          },
        ]),
        { provide: ViewportScroller, useValue: scroller },
      ],
    });
    router = TestBed.inject(Router);
    harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/rabbitmq/guide');
    memory = harness.routeDebugElement!.injector.get(AreaScrollMemory);
    scroller.calls.length = 0;
  });

  /** Deja pasar los fotogramas en los que se devuelve la posición. */
  const frames = () => new Promise((resolve) => setTimeout(resolve, 80));

  async function go(url: string) {
    await router.navigateByUrl(url);
    await frames();
  }

  it('returns to where the user was when coming back to an area (the guide example)', async () => {
    scroller.position = 3200;

    await go('/rabbitmq/exchanges');
    await go('/rabbitmq/guide');

    expect(scroller.position).toBe(3200);
  });

  it('remembers each area on its own', async () => {
    scroller.position = 3200;
    await go('/rabbitmq/exchanges');
    scroller.position = 150;
    await go('/rabbitmq/queues');
    scroller.position = 700;

    await go('/rabbitmq/guide');
    expect(scroller.position).toBe(3200);
    await go('/rabbitmq/exchanges');
    expect(scroller.position).toBe(150);
    await go('/rabbitmq/queues');
    expect(scroller.position).toBe(700);
  });

  it('keeps remembering after several round trips', async () => {
    for (const y of [500, 1800, 2600]) {
      scroller.position = y;
      await go('/rabbitmq/exchanges');
      await go('/rabbitmq/guide');
      expect(scroller.position).toBe(y);
    }
  });

  it('starts an area visited for the first time at the top, not wherever the previous one was', async () => {
    scroller.position = 3200;

    await go('/rabbitmq/exchanges');

    expect(scroller.position).toBe(0);
    expect(memory.positionOf('exchanges')).toBeUndefined();
  });

  it('records the position of the area being left', async () => {
    scroller.position = 1234;

    await go('/rabbitmq/exchanges');

    expect(memory.positionOf('guide')).toBe(1234);
  });

  it('does not move the page when jumping to an anchor inside the same area (the guide index)', async () => {
    scroller.position = 900;

    await go('/rabbitmq/guide#glosario');

    expect(scroller.calls).toEqual([]);
    expect(scroller.position).toBe(900);
  });

  it('does not override an anchor when arriving at another area with one', async () => {
    scroller.position = 900;
    await go('/rabbitmq/exchanges');
    scroller.calls.length = 0;

    await go('/rabbitmq/guide#glosario');

    expect(scroller.calls).toEqual([]);
  });

  it('does not touch the scroll when the area does not change', async () => {
    scroller.position = 900;

    await go('/rabbitmq/guide?otra=cosa');

    expect(scroller.calls).toEqual([]);
  });

  it('keeps trying while the area loads its content and is still too short, then stays where it should', async () => {
    scroller.position = 2000;
    await go('/rabbitmq/exchanges');
    scroller.maxScroll = 300;

    const navigation = router.navigateByUrl('/rabbitmq/guide');
    await navigation;
    await new Promise((resolve) => setTimeout(resolve, 40));
    expect(scroller.position).toBe(300);
    scroller.maxScroll = 5000;
    await new Promise((resolve) => setTimeout(resolve, 200));

    expect(scroller.position).toBe(2000);
  });

  it('forgets the old target when the user navigates again before it is reached', async () => {
    scroller.position = 2000;
    await go('/rabbitmq/exchanges');
    scroller.position = 400;
    scroller.maxScroll = 10;
    await router.navigateByUrl('/rabbitmq/guide');

    scroller.maxScroll = Number.POSITIVE_INFINITY;
    await go('/rabbitmq/queues');

    expect(scroller.position).toBe(0);
  });

  it('ignores navigations outside the lab', async () => {
    scroller.position = 800;

    await go('/status');

    expect(scroller.calls).toEqual([]);
    expect(memory.positionOf('guide')).toBe(800);
  });

  it('stops listening when it is destroyed', async () => {
    TestBed.resetTestingModule();
    await new Promise((resolve) => setTimeout(resolve, 0));

    expect(() => memory.ngOnDestroy()).not.toThrow();
  });
});
