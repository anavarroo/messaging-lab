import { ComponentFixture, TestBed } from '@angular/core/testing';
import { RabbitMqDestinationForm } from './rabbitmq-destination-form';

describe('RabbitMqDestinationForm', () => {
  let fixture: ComponentFixture<RabbitMqDestinationForm>;
  let component: RabbitMqDestinationForm;
  let root: HTMLElement;

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [RabbitMqDestinationForm] }).compileComponents();
    fixture = TestBed.createComponent(RabbitMqDestinationForm);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('exchanges', [
      { name: '', type: 'DIRECT', predefined: true },
      { name: 'pizzeria', type: 'TOPIC', predefined: false },
    ]);
    fixture.componentRef.setInput('queues', [{ name: 'cocina', ready: 0, unacknowledged: 0, total: 0 }]);
    root = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
  });

  const choose = (selector: string, value: string) => {
    const field = root.querySelector<HTMLSelectElement>(selector)!;
    field.value = value;
    field.dispatchEvent(new Event('change'));
    fixture.detectChanges();
  };
  const typeKey = (value: string) => {
    const input = root.querySelector<HTMLInputElement>('#destination-routing-key')!;
    input.value = value;
    input.dispatchEvent(new Event('input'));
  };
  const error = () => root.querySelector('.field-error')?.textContent?.trim();

  it('offers the exchanges, naming the default one', () => {
    const options = Array.from(root.querySelectorAll('#destination-exchange option')).map((o) => o.textContent?.trim());
    expect(options).toEqual(['Elige un exchange…', '(exchange por defecto)', 'pizzeria']);
  });

  it('returns an exchange with its routing key', () => {
    choose('#destination-exchange', 'pizzeria');
    typeKey('pedido.pizza');

    expect(component.read()).toEqual({ type: 'EXCHANGE', exchange: 'pizzeria', routingKey: 'pedido.pizza' });
  });

  it('returns a queue after switching to direct publishing', () => {
    root.querySelector<HTMLInputElement>('input[value=QUEUE]')!.click();
    fixture.detectChanges();
    expect(root.querySelector('#destination-exchange')).toBeNull();

    choose('#destination-queue', 'cocina');

    expect(component.read()).toEqual({ type: 'QUEUE', queue: 'cocina' });
  });

  it('asks for the exchange or the queue and returns nothing while missing', () => {
    expect(error()).toBe('');

    expect(component.read()).toBeNull();
    fixture.detectChanges();
    expect(error()).toBe('Elige el exchange al que publicar.');

    root.querySelector<HTMLInputElement>('input[value=QUEUE]')!.click();
    fixture.detectChanges();
    expect(component.read()).toBeNull();
    fixture.detectChanges();
    expect(error()).toBe('Elige la cola a la que publicar.');
  });

  it('suggests a routing key without overwriting one already typed', () => {
    component.suggestRoutingKey('lab.temperatura');
    fixture.detectChanges();
    expect(root.querySelector<HTMLInputElement>('#destination-routing-key')!.value).toBe('lab.temperatura');

    typeKey('mia');
    component.suggestRoutingKey('lab.alarma');
    fixture.detectChanges();
    expect(root.querySelector<HTMLInputElement>('#destination-routing-key')!.value).toBe('mia');
  });

  it('goes back to the exchange option on reset', () => {
    root.querySelector<HTMLInputElement>('input[value=QUEUE]')!.click();
    component.reset();
    fixture.detectChanges();

    expect(root.querySelector('#destination-exchange')).not.toBeNull();
  });
});
