import { ViewportScroller } from '@angular/common';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { NEVER } from 'rxjs';
import { App } from './app';
import { routes } from './app.routes';
import { StatusApiService } from './core/status/status-api.service';
import { FakeViewportScroller } from './testing/fake-viewport-scroller';

describe('App', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [
        provideRouter(routes),
        { provide: ViewportScroller, useClass: FakeViewportScroller },
        { provide: StatusApiService, useValue: { getStatus: () => NEVER } },
      ],
    }).compileComponents();
  });

  it('should create the app', () => {
    const fixture = TestBed.createComponent(App);
    expect(fixture.componentInstance).toBeTruthy();
  });

  it('renders the shell with the status page on the initial route', async () => {
    const fixture = TestBed.createComponent(App);
    await TestBed.inject(Router).navigateByUrl('/');
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;

    expect(page.querySelector('header h1')?.textContent).toContain('messaging-lab');
    expect(page.querySelector('nav')).not.toBeNull();
    expect(page.querySelector('main app-status-page')).not.toBeNull();
    expect(page.textContent).toContain('Estado del backend');
  });
});
