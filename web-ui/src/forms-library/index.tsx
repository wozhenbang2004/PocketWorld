import { useId, type ComponentProps, type ReactNode } from 'react';
import { Input } from './boardui/components/base/input/input';
import { Textarea } from './boardui/components/base/textarea/textarea';
import { Select, SelectItem } from './boardui/components/base/select/select';
import { ComboBox, ComboBoxItem } from './boardui/components/base/combobox/combobox';
import { Radio, RadioGroup } from './boardui/components/base/radio/radio';

export { Input as TextField } from './boardui/components/base/input/input';
export { Textarea as TextAreaField } from './boardui/components/base/textarea/textarea';
export { SearchField } from './boardui/components/base/search-field/search-field';
export { Checkbox as CheckboxField } from './boardui/components/base/checkbox/checkbox';
export { Accordion, AccordionItem } from './boardui/components/base/accordion/accordion';
export { Button, ButtonLink } from './boardui/components/base/buttons/button';
export { IconButton } from './boardui/components/base/buttons/icon-button';
export { StretchSwitch } from './StretchSwitch';
export { SettingsDialog } from './SettingsDialog';
export { ObjectTable, type ObjectColumn } from './ObjectTable';
export { HostFileUpload, formatFileSize } from './HostFileUpload';

export interface FieldOption { id: string; label: string; description?: string; disabled?: boolean; }
export interface SelectionFieldProps {
  label: string; options: FieldOption[]; value: string; onChange: (value: string) => void;
  isDisabled?: boolean; hint?: ReactNode; placeholder?: string; className?: string;
}
/** Controlled field composition around the actual BoardUI Select source. */
export function SelectField({ label, options, value, onChange, isDisabled, hint, placeholder = '请选择', className, compactValue = false }: SelectionFieldProps & { compactValue?: boolean }) {
  const hintId = useId();
  return <div className={`pw-selection-field ${className ?? ''}`}>
    <span className="pw-field-label">{label}</span>
    <Select aria-label={label} aria-describedby={hint ? hintId : undefined} selectedKey={value || null}
      onSelectionChange={key => { if (key != null) onChange(String(key)); }} isDisabled={isDisabled}
      placeholder={placeholder} triggerClassName="pw-select-trigger" popoverClassName="pw-select-popover"
      renderValue={compactValue ? options.find(option => option.id === value)?.label : undefined}>
      {options.map(option => <SelectItem key={option.id} id={option.id} textValue={option.label} isDisabled={option.disabled}>
        <span className="pw-option-copy"><span>{option.label}</span>{option.description && <small>{option.description}</small>}</span>
      </SelectItem>)}
    </Select>
    {hint && <span id={hintId} className="pw-field-hint">{hint}</span>}
  </div>;
}
/** Uses BoardUI's real React Aria filtering/selection and popup behavior. */
export function ComboBoxField({ label, options, value, onChange, isDisabled, hint, placeholder = '搜索并选择', className }: SelectionFieldProps) {
  return <ComboBox<FieldOption> label={label} hint={hint} placeholder={placeholder} emptyMessage="没有匹配项"
    className={`pw-combobox-field ${className ?? ''}`} defaultItems={options} selectedKey={value || null}
    onSelectionChange={key => { if (key != null) onChange(String(key)); }} isDisabled={isDisabled}>
    {option => <ComboBoxItem key={option.id} id={option.id} textValue={option.label} isDisabled={option.disabled}>
      <span className="pw-option-copy"><span>{option.label}</span>{option.description && <small>{option.description}</small>}</span>
    </ComboBoxItem>}
  </ComboBox>;
}
export function RadioOptions({ label, options, value, onChange, isDisabled = false, className }: SelectionFieldProps) {
  return <div className={`pw-selection-field ${className ?? ''}`}><span className="pw-field-label">{label}</span>
    <RadioGroup aria-label={label} value={value} onChange={onChange} isDisabled={isDisabled}>
      {options.map(option => <Radio key={option.id} value={option.id} isDisabled={option.disabled} className="pw-radio-option">
        <span className="pw-option-copy"><span>{option.label}</span>{option.description && <small>{option.description}</small>}</span>
      </Radio>)}
    </RadioGroup>
  </div>;
}

// Explicit exports make the original props available to product adapters.
export type TextFieldProps = ComponentProps<typeof Input>;
export type TextAreaFieldProps = ComponentProps<typeof Textarea>;
