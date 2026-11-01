import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { Shell } from './shell';

@Component({ template: '<p>Contenido de estado</p>' })
class StatusStub {}

@Component({ template: '<p>Contenido del laboratorio</p>' })
class LabStub {}

describe('Shell', () => {
  const disabledSections = [
    {
      label: 'Laboratorio Kafka',
      description: 'Topics, particiones, offsets, productores, consumer groups y lag.',
    },
  ];

  let fixture: ComponentFixture<Shell>;
  let page: HTMLElement;
  let router: Router;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Shell],
      providers: [
        provideRouter([
          { path: 'status', component: StatusStub },
          { path: 'rabbitmq', component: LabStub },
        ]),
      ],
    }).compileComponents();

    router = TestBed.inject(Router);
    fixture = TestBed.createComponent(Shell);
    page = fixture.nativeElement as HTMLElement;
    await router.navigateByUrl('/status');
    await settle();
  });

  async function settle() {
    fixture.detectChanges();
    await fixture.whenStable();
  }

  const disabledButtons = () =>
    Array.from(page.querySelectorAll<HTMLButtonElement>('button[aria-disabled="true"]'));
  const navLink = (label: string) =>
    Array.from(page.querySelectorAll<HTMLAnchorElement>('nav a')).find((link) =>
      link.textContent?.includes(label),
    ) as HTMLAnchorElement;
  const notice = () => page.querySelector('[role="status"]')?.textContent?.trim();

  it('shows the lab name in the header', () => {
    expect(page.querySelector('header h1')?.textContent).toContain('messaging-lab');
  });

  it('renders the router content', () => {
    expect(page.querySelector('main')?.textContent).toContain('Contenido de estado');
  });

  it('shows the status section as an active link', () => {
    const link = navLink('Estado');

    expect(link?.textContent).toContain('Estado');
    expect(link?.getAttribute('href')).toBe('/status');
    expect(link?.classList).toContain('active');
    expect(link?.getAttribute('aria-current')).toBe('page');
  });

  it('shows the RabbitMQ lab as an enabled link to /rabbitmq (CA-3.1)', () => {
    const link = navLink('Laboratorio RabbitMQ');

    expect(link.tagName).toBe('A');
    expect(link.getAttribute('href')).toBe('/rabbitmq');
    expect(link.hasAttribute('aria-disabled')).toBe(false);
    expect(
      disabledButtons().some((button) => button.textContent?.includes('Laboratorio RabbitMQ')),
    ).toBe(false);
  });

  it('has the assistant button at the right of the header, on every screen (spec 06, CA-1.1)', () => {
    const header = page.querySelector('header.header')!;

    expect(header.querySelector('app-assistant-widget button.toggle')).not.toBeNull();
    expect(header.querySelector('h1')?.nextElementSibling?.tagName.toLowerCase()).toBe('app-assistant-widget');
  });

  it('navigates to the RabbitMQ lab and marks the section as active (CA-3.1, CA-3.4)', async () => {
    navLink('Laboratorio RabbitMQ').click();
    await settle();

    expect(router.url).toBe('/rabbitmq');
    expect(page.querySelector('main')?.textContent).toContain('Contenido del laboratorio');
    expect(navLink('Laboratorio RabbitMQ').classList).toContain('active');
    expect(navLink('Laboratorio RabbitMQ').getAttribute('aria-current')).toBe('page');
    expect(navLink('Estado').classList).not.toContain('active');
  });

  it('keeps only the Kafka lab disabled; the flow visualizer lives inside each lab (CA-1.2)', () => {
    expect(disabledButtons().map((button) => button.querySelector('.label')?.textContent)).toEqual(['Laboratorio Kafka']);
    expect(page.textContent).not.toContain('Visualizador de flujo');
  });

  it('shows the future section disabled with its description', () => {
    const buttons = disabledButtons();

    expect(buttons).toHaveLength(disabledSections.length);
    disabledSections.forEach((section, index) => {
      expect(buttons[index].textContent).toContain(section.label);
      expect(buttons[index].textContent).toContain(section.description);
    });
  });

  it('does not show a notice until a disabled section is activated', () => {
    expect(notice()).toBe('');
  });

  it.each(disabledSections.map((section, index) => [section.label, index] as const))(
    'shows "Disponible próximamente" without navigating when activating %s',
    async (label, index) => {
      const urlBefore = router.url;

      disabledButtons()[index].click();
      await settle();

      expect(notice()).toContain('Disponible próximamente');
      expect(notice()).toContain(label);
      expect(router.url).toBe(urlBefore);
      expect(router.url).toBe('/status');
    },
  );

  it('keeps the disabled sections focusable so they can be activated with the keyboard', () => {
    disabledButtons().forEach((button) => {
      expect(button.tagName).toBe('BUTTON');
      expect(button.disabled).toBe(false);
    });
  });
});
