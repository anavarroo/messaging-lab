import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { NEVER } from 'rxjs';
import { routes } from './app.routes';
import { StatusApiService } from './core/status/status-api.service';
import { StatusPage } from './features/status/status-page/status-page';

describe('routes', () => {
  let harness: RouterTestingHarness;
  let router: Router;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter(routes),
        { provide: StatusApiService, useValue: { getStatus: () => NEVER } },
      ],
    });
    router = TestBed.inject(Router);
    harness = await RouterTestingHarness.create();
  });

  it('redirects the empty path to the status page', async () => {
    const page = await harness.navigateByUrl('/');

    expect(router.url).toBe('/status');
    expect(page).toBeInstanceOf(StatusPage);
  });

  it('shows the status page at /status', async () => {
    const page = await harness.navigateByUrl('/status');

    expect(router.url).toBe('/status');
    expect(page).toBeInstanceOf(StatusPage);
  });

  it('redirects an unknown address to the status page', async () => {
    const page = await harness.navigateByUrl('/no-existe');

    expect(router.url).toBe('/status');
    expect(page).toBeInstanceOf(StatusPage);
  });

  it('redirects an unknown nested address to the status page', async () => {
    await harness.navigateByUrl('/laboratorio/rabbitmq/colas');

    expect(router.url).toBe('/status');
  });
});
